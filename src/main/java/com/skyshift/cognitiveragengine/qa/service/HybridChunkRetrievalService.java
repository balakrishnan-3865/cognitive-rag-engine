package com.skyshift.cognitiveragengine.qa.service;

import com.skyshift.cognitiveragengine.qa.config.RetrievalProperties;
import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.model.KeywordHit;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.service.ElasticsearchChunkIndexService;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.VectorSearchService;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.model.VectorHit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class HybridChunkRetrievalService {

    private static final String OBSERVATION_NAME = "rag.hybrid_retrieval";

    private final VectorSearchService vectorSearchService;
    private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;
    private final RetrievalProperties retrievalProperties;
    private final ObservationRegistry observationRegistry;
    private final DistributionSummary denseHitsSummary;
    private final DistributionSummary sparseHitsSummary;
    private final DistributionSummary fusedCountSummary;
    private final Counter sparseDegradedCounter;

    public HybridChunkRetrievalService(
            VectorSearchService vectorSearchService,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            RetrievalProperties retrievalProperties,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry
    ) {
        this.vectorSearchService = vectorSearchService;
        this.elasticsearchChunkIndexService = elasticsearchChunkIndexService;
        this.retrievalProperties = retrievalProperties;
        this.observationRegistry = observationRegistry;
        this.denseHitsSummary = DistributionSummary.builder("rag.retrieval.dense.hits")
                .description("Dense (pgvector) candidate hits returned before RRF fusion")
                .register(meterRegistry);
        this.sparseHitsSummary = DistributionSummary.builder("rag.retrieval.sparse.hits")
                .description("Sparse (Elasticsearch) candidate hits returned before RRF fusion")
                .register(meterRegistry);
        this.fusedCountSummary = DistributionSummary.builder("rag.retrieval.fused.count")
                .description("Distinct chunks remaining after RRF fusion, before topK truncation")
                .register(meterRegistry);
        this.sparseDegradedCounter = Counter.builder("rag.retrieval.sparse.degraded")
                .description("Requests where sparse (Elasticsearch) search failed and fusion degraded to dense-only")
                .register(meterRegistry);
    }

    public DocumentBundle retrieveRelevantChunks(String query, Long groupId, int topK) {
        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("groupId", String.valueOf(groupId));

        return observation.observe(() -> {
            int denseCandidatePoolSize = retrievalProperties.getDense().getTopK();

            List<VectorHit> denseHits = vectorSearchService.search(query, groupId, denseCandidatePoolSize);
            log.debug("Retrieved {} dense (vector) candidates for fusion", denseHits.size());
            denseHitsSummary.record(denseHits.size());

            int sparseCandidatePoolSize = retrievalProperties.getSparse().getTopK();

            AtomicBoolean sparseDegraded = new AtomicBoolean(false);
            List<KeywordHit> sparseHits = searchSparseChunks(query, groupId, sparseCandidatePoolSize, sparseDegraded);
            log.debug("Retrieved {} sparse (keyword) candidates for fusion", sparseHits.size());
            sparseHitsSummary.record(sparseHits.size());

            int rrfK = retrievalProperties.getFusion().getRrfK();
            List<RankedChunk> fused = fuseWithReciprocalRankFusion(denseHits, sparseHits, rrfK);
            log.info("RRF fusion produced {} distinct chunks from {} dense + {} sparse candidates",
                    fused.size(), denseHits.size(), sparseHits.size());
            fusedCountSummary.record(fused.size());

            List<Document> documents = fused.stream()
                    .sorted(Comparator.comparingDouble(RankedChunk::rrfScore).reversed())
                    .limit(topK)
                    .map(this::toDocument)
                    .collect(Collectors.toList());

            // groupId/sparseDegraded are bounded-cardinality and become metric tags on the
            // rag.hybrid_retrieval timer; per-chunk detail is trace-only (unbounded cardinality
            // would blow up the metric backend if used as a tag).
            observation
                    .lowCardinalityKeyValue("sparseDegraded", String.valueOf(sparseDegraded.get()))
                    .highCardinalityKeyValue("denseHitCount", String.valueOf(denseHits.size()))
                    .highCardinalityKeyValue("sparseHitCount", String.valueOf(sparseHits.size()))
                    .highCardinalityKeyValue("fusedChunkIds", fused.stream()
                            .map(chunk -> chunk.chunkId() + ":" + String.format("%.4f", chunk.rrfScore()))
                            .collect(Collectors.joining(",")));

            return new DocumentBundle(documents);
        });
    }

    /**
     * Elasticsearch is the secondary retriever here (pgvector remains primary, matching the
     * eventually-consistent treatment of ES elsewhere in the ingestion flow). A sparse-search
     * failure degrades to dense-only fusion instead of failing the whole QA request.
     */
    private List<KeywordHit> searchSparseChunks(String query, Long groupId, int candidatePoolSize, AtomicBoolean sparseDegraded) {
        try {
            return elasticsearchChunkIndexService.searchChunks(query, groupId, candidatePoolSize);
        } catch (Exception e) {
            log.error("Sparse (Elasticsearch) search failed, degrading to dense-only results: {}", e.getMessage(), e);
            sparseDegradedCounter.increment();
            sparseDegraded.set(true);
            return List.of();
        }
    }

    /**
     * chunkId is the fusion key: it is the one identifier populated identically by both
     * VectorSearchService and ElasticsearchChunkIndexService for the same source chunk,
     * unlike documentId (many chunks per document) or the vector store's own document id
     * (a store-generated UUID with no ES equivalent).
     */
    private List<RankedChunk> fuseWithReciprocalRankFusion(List<VectorHit> denseHits, List<KeywordHit> sparseHits, int rrfK) {
        Map<Long, RankedChunk> fusedByChunkId = new LinkedHashMap<>();

        for (int rank = 0; rank < denseHits.size(); rank++) {
            VectorHit hit = denseHits.get(rank);
            RankedChunk chunk = fusedByChunkId.computeIfAbsent(hit.chunkId(),
                    id -> new RankedChunk(id, hit.documentId(), hit.chunkNumber(), hit.content()));
            chunk.applyRank(rank + 1, rrfK);
        }

        for (int rank = 0; rank < sparseHits.size(); rank++) {
            KeywordHit hit = sparseHits.get(rank);
            RankedChunk chunk = fusedByChunkId.computeIfAbsent(hit.chunkId(),
                    id -> new RankedChunk(id, hit.documentId(), hit.chunkIndex(), hit.chunkText()));
            chunk.applyRank(rank + 1, rrfK);
        }

        return new ArrayList<>(fusedByChunkId.values());
    }

    private Document toDocument(RankedChunk chunk) {
        return new Document(
                chunk.content(),
                Map.of(
                        "chunkId", chunk.chunkId().toString(),
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
        private final Long chunkId;
        private final Long documentId;
        private final Integer chunkNumber;
        private final String content;
        private double rrfScore = 0.0;

        RankedChunk(Long chunkId, Long documentId, Integer chunkNumber, String content) {
            this.chunkId = chunkId;
            this.documentId = documentId;
            this.chunkNumber = chunkNumber;
            this.content = content;
        }

        void applyRank(int rank, int rrfK) {
            rrfScore += 1.0 / (rrfK + rank);
        }

        Long chunkId() {
            return chunkId;
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