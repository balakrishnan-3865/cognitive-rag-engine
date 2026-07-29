package com.skyshift.cognitiveragengine.qa.service;

import com.skyshift.cognitiveragengine.qa.config.HybridSearchProperties;
import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.model.KeywordHit;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.service.ElasticsearchChunkIndexService;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.VectorSearchService;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.model.VectorHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class HybridChunkRetrievalService {

    private static final int RRF_K = 60;

    private final VectorSearchService vectorSearchService;
    private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;
    private final HybridSearchProperties hybridSearchProperties;

    public HybridChunkRetrievalService(
            VectorSearchService vectorSearchService,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            HybridSearchProperties hybridSearchProperties
    ) {
        this.vectorSearchService = vectorSearchService;
        this.elasticsearchChunkIndexService = elasticsearchChunkIndexService;
        this.hybridSearchProperties = hybridSearchProperties;
    }

    public DocumentBundle retrieveRelevantChunks(String query, Long groupId, int topK) {
        int candidatePoolSize = hybridSearchProperties.getCandidatePoolSize();

        List<VectorHit> denseHits = vectorSearchService.search(query, groupId, candidatePoolSize);
        log.debug("Retrieved {} dense (vector) candidates for fusion", denseHits.size());

        List<KeywordHit> sparseHits = searchSparseChunks(query, groupId, candidatePoolSize);
        log.debug("Retrieved {} sparse (keyword) candidates for fusion", sparseHits.size());

        List<RankedChunk> fused = fuseWithReciprocalRankFusion(denseHits, sparseHits);
        log.info("RRF fusion produced {} distinct chunks from {} dense + {} sparse candidates",
                fused.size(), denseHits.size(), sparseHits.size());

        List<Document> documents = fused.stream()
                .sorted(Comparator.comparingDouble(RankedChunk::rrfScore).reversed())
                .limit(topK)
                .map(this::toDocument)
                .collect(Collectors.toList());

        return new DocumentBundle(documents);
    }

    /**
     * Elasticsearch is the secondary retriever here (pgvector remains primary, matching the
     * eventually-consistent treatment of ES elsewhere in the ingestion flow). A sparse-search
     * failure degrades to dense-only fusion instead of failing the whole QA request.
     */
    private List<KeywordHit> searchSparseChunks(String query, Long groupId, int candidatePoolSize) {
        try {
            return elasticsearchChunkIndexService.searchChunks(query, groupId, candidatePoolSize);
        } catch (Exception e) {
            log.error("Sparse (Elasticsearch) search failed, degrading to dense-only results: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * chunkId is the fusion key: it is the one identifier populated identically by both
     * VectorSearchService and ElasticsearchChunkIndexService for the same source chunk,
     * unlike documentId (many chunks per document) or the vector store's own document id
     * (a store-generated UUID with no ES equivalent).
     */
    private List<RankedChunk> fuseWithReciprocalRankFusion(List<VectorHit> denseHits, List<KeywordHit> sparseHits) {
        Map<Long, RankedChunk> fusedByChunkId = new LinkedHashMap<>();

        for (int rank = 0; rank < denseHits.size(); rank++) {
            VectorHit hit = denseHits.get(rank);
            RankedChunk chunk = fusedByChunkId.computeIfAbsent(hit.chunkId(),
                    id -> new RankedChunk(hit.documentId(), hit.chunkNumber(), hit.content()));
            chunk.applyRank(rank + 1);
        }

        for (int rank = 0; rank < sparseHits.size(); rank++) {
            KeywordHit hit = sparseHits.get(rank);
            RankedChunk chunk = fusedByChunkId.computeIfAbsent(hit.chunkId(),
                    id -> new RankedChunk(hit.documentId(), hit.chunkIndex(), hit.chunkText()));
            chunk.applyRank(rank + 1);
        }

        return new ArrayList<>(fusedByChunkId.values());
    }

    private Document toDocument(RankedChunk chunk) {
        return new Document(
                chunk.content(),
                Map.of(
                        "documentId", chunk.documentId().toString(),
                        "chunkNumber", chunk.chunkNumber().toString(),
                        "similarity", String.valueOf(chunk.rrfScore())
                )
        );
    }

    /**
     * Accumulates RRF score contributions for a single chunk across the dense and sparse
     * ranked lists. "similarity" downstream (see #toDocument) is the fused RRF score, not a
     * raw cosine/BM25 score - the two are on different scales and RRF is what was ranked by.
     */
    private static final class RankedChunk {
        private final Long documentId;
        private final Integer chunkNumber;
        private final String content;
        private double rrfScore = 0.0;

        RankedChunk(Long documentId, Integer chunkNumber, String content) {
            this.documentId = documentId;
            this.chunkNumber = chunkNumber;
            this.content = content;
        }

        void applyRank(int rank) {
            rrfScore += 1.0 / (RRF_K + rank);
        }

        Long documentId() {
            return documentId;
        }

        Integer chunkNumber() {
            return chunkNumber;
        }

        String content() {
            return content;
        }

        double rrfScore() {
            return rrfScore;
        }
    }
}