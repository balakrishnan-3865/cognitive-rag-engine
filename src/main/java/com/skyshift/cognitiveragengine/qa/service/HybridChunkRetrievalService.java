package com.skyshift.cognitiveragengine.qa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.common.observability.ObservabilityProperties;
import com.skyshift.cognitiveragengine.document.service.DocumentService;
import com.skyshift.cognitiveragengine.qa.config.RetrievalProperties;
import com.skyshift.cognitiveragengine.qa.exception.RetrievalException;
import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import com.skyshift.cognitiveragengine.qa.model.RetrievalResult;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.model.KeywordHit;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.service.ElasticsearchChunkIndexService;
import com.skyshift.cognitiveragengine.retrieval.reranker.service.CrossEncoderReranker;
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
import java.util.stream.Collectors;

@Slf4j
@Service
public class HybridChunkRetrievalService {

    private static final String OBSERVATION_NAME = "rag.hybrid_retrieval";
    private static final String ELASTICSEARCH_OBSERVATION_NAME = "elasticsearch_query";

    private final VectorSearchService vectorSearchService;
    private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;
    private final DocumentService documentService;
    private final RetrievalProperties retrievalProperties;
    private final CrossEncoderReranker crossEncoderReranker;
    private final ObservationRegistry observationRegistry;
    private final ObjectMapper objectMapper;
    private final ObservabilityProperties observabilityProperties;
    private final DistributionSummary denseHitsSummary;
    private final DistributionSummary sparseHitsSummary;
    private final DistributionSummary fusedCountSummary;
    private final Counter denseRetrievalFailures;
    private final Counter sparseRetrievalFailures;
    private final Counter hybridRetrievalSuccess;
    private final Counter degradedRetrievalSuccess;

    public HybridChunkRetrievalService(
            VectorSearchService vectorSearchService,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            DocumentService documentService,
            RetrievalProperties retrievalProperties,
            CrossEncoderReranker crossEncoderReranker,
            ObservationRegistry observationRegistry,
            ObjectMapper objectMapper,
            ObservabilityProperties observabilityProperties,
            MeterRegistry meterRegistry
    ) {
        this.vectorSearchService = vectorSearchService;
        this.elasticsearchChunkIndexService = elasticsearchChunkIndexService;
        this.documentService = documentService;
        this.retrievalProperties = retrievalProperties;
        this.crossEncoderReranker = crossEncoderReranker;
        this.observationRegistry = observationRegistry;
        this.objectMapper = objectMapper;
        this.observabilityProperties = observabilityProperties;
        this.denseHitsSummary = DistributionSummary.builder("rag.retrieval.dense.hits")
                .description("Dense (pgvector) search result hit count")
                .register(meterRegistry);
        this.sparseHitsSummary = DistributionSummary.builder("rag.retrieval.sparse.hits")
                .description("Sparse (Elasticsearch) search result hit count")
                .register(meterRegistry);
        this.fusedCountSummary = DistributionSummary.builder("rag.retrieval.fused.count")
                .description("Distinct chunks remaining after RRF fusion")
                .register(meterRegistry);
        this.denseRetrievalFailures = Counter.builder("rag.retrieval.dense.failures")
                .description("Dense (pgvector) search failed")
                .register(meterRegistry);
        this.sparseRetrievalFailures = Counter.builder("rag.retrieval.sparse.failures")
                .description("Sparse (Elasticsearch) search failed")
                .register(meterRegistry);
        this.hybridRetrievalSuccess = Counter.builder("rag.retrieval.success")
                .tag("outcome", "hybrid")
                .description("Both sources succeeded - hybrid ranking used")
                .register(meterRegistry);
        this.degradedRetrievalSuccess = Counter.builder("rag.retrieval.success")
                .tag("outcome", "degraded")
                .description("One source succeeded - degraded to single source")
                .register(meterRegistry);
    }

    /**
     * Orchestrates hybrid retrieval: attempts both dense and sparse searches independently,
     * then uses available results based on success state.
     *
     * Success cases:
     * - Both succeeded → fuse with RRF (best quality, "hybrid")
     * - Dense only → use dense results ("dense_only")
     * - Sparse only → use sparse results ("sparse_only")
     *
     * Failure case:
     * - Both failed → throw RetrievalException (data integrity issue)
     */
    public DocumentBundle retrieveRelevantChunks(String query, Long groupId, int topK) {
        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("groupId", String.valueOf(groupId));

        return observation.observe(() -> {
            List<Long> documentIds = documentService.findCurrentReadyDocumentIds(groupId);
            if (documentIds.isEmpty()) {
                log.debug("No current READY documents for groupId={}, skipping retrieval", groupId);
                return new DocumentBundle(List.of());
            }

            RetrievalResult denseResult = attemptDenseSearch(query, groupId, documentIds);
            RetrievalResult sparseResult = attemptSparseSearch(query, groupId, documentIds);

            DocumentBundle bundle = handleRetrievalOutcome(
                    denseResult, sparseResult, query, groupId, topK, observation);

            return bundle;
        });
    }

    /**
     * Attempts dense (pgvector) retrieval independently.
     * Returns success with results or failure with exception, never throws.
     */
    private RetrievalResult attemptDenseSearch(String query, Long groupId, List<Long> documentIds) {
        try {
            int topK = retrievalProperties.getDense().getTopK();
            List<VectorHit> results = vectorSearchService.search(query, groupId, documentIds, topK);
            log.debug("Dense search succeeded: {} hits for groupId={}", results.size(), groupId);
            return RetrievalResult.success("dense", results);
        } catch (Exception e) {
            log.error("Dense search failed for groupId={}: {}", groupId, e.getMessage(), e);
            return RetrievalResult.failure("dense", e);
        }
    }

    /**
     * Attempts sparse (Elasticsearch) retrieval independently.
     * Returns success with results or failure with exception, never throws.
     */
    private RetrievalResult attemptSparseSearch(String query, Long groupId, List<Long> documentIds) {
        Observation sparseObservation = Observation.createNotStarted(ELASTICSEARCH_OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("db.system", "elasticsearch")
                .lowCardinalityKeyValue("db.operation.name", "query")
                .lowCardinalityKeyValue("db.collection.name", "rag_sparse_chunks") // mirrors ElasticsearchChunkIndexService.CHUNK_INDEX_NAME
                .highCardinalityKeyValue("db.query.text", query)
                .highCardinalityKeyValue("db.elasticsearch.top_k", String.valueOf(retrievalProperties.getSparse().getTopK()))
                .highCardinalityKeyValue("db.elasticsearch.min_score_percentile",
                        String.valueOf(retrievalProperties.getSparse().getMinScorePercentile()));

        return sparseObservation.observe(() -> {
            try {
                int topK = retrievalProperties.getSparse().getTopK();
                List<KeywordHit> results = elasticsearchChunkIndexService.searchChunks(query, groupId, documentIds, topK);
                sparseObservation.highCardinalityKeyValue("db.response.returned_rows", String.valueOf(results.size()));
                if (observabilityProperties.isCaptureContent()) {
                    String summary = serializeKeywordHits(results);
                    if (summary != null) {
                        sparseObservation.highCardinalityKeyValue("db.response.returned_documents", summary);
                    }
                }
                log.debug("Sparse search succeeded: {} hits for groupId={}", results.size(), groupId);
                return RetrievalResult.success("sparse", results);
            } catch (Exception e) {
                log.error("Sparse search failed for groupId={}: {}", groupId, e.getMessage(), e);
                return RetrievalResult.failure("sparse", e);
            }
        });
    }

    /**
     * Compact chunkId + score summary, not full chunk text - mirrors the pgvector query span's
     * VectorStoreContentObservationConvention so both raw retrieval sources are comparable in the
     * trace without duplicating text that only the handful of chunks reaching the LLM need.
     */
    private String serializeKeywordHits(List<KeywordHit> hits) {
        try {
            List<Map<String, Object>> summary = hits.stream()
                    .map(hit -> Map.<String, Object>of(
                            "chunkId", String.valueOf(hit.chunkId()),
                            "score", hit.normalizedScore()))
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            log.debug("Failed to serialize sparse search results for tracing: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Handles retrieval outcomes based on which sources succeeded.
     *
     * Uses available data and fails only when both sources failed (data integrity issue).
     * No results (empty list) is NOT an error - it's a valid outcome.
     */
    private DocumentBundle handleRetrievalOutcome(
            RetrievalResult denseResult,
            RetrievalResult sparseResult,
            String query,
            Long groupId,
            int topK,
            Observation observation) {

        // CASE 1: Both succeeded → optimal path (hybrid ranking with RRF)
        if (denseResult.isSuccess() && sparseResult.isSuccess()) {
            log.info("Hybrid retrieval successful: dense={} hits, sparse={} hits, groupId={}",
                    denseResult.getHitCount(), sparseResult.getHitCount(), groupId);

            List<VectorHit> denseHits = (List<VectorHit>) denseResult.getResults();
            List<KeywordHit> sparseHits = (List<KeywordHit>) sparseResult.getResults();
            denseHitsSummary.record(denseHits.size());
            sparseHitsSummary.record(sparseHits.size());

            int rrfK = retrievalProperties.getFusion().getRrfK();
            List<RankedChunk> fused = fuseWithReciprocalRankFusion(denseHits, sparseHits, rrfK);
            log.info("RRF fusion produced {} distinct chunks from {} dense + {} sparse candidates",
                    fused.size(), denseHits.size(), sparseHits.size());
            fusedCountSummary.record(fused.size());

            List<Document> fusedDocuments = fused.stream()
                    .sorted(Comparator.comparingDouble(RankedChunk::rrfScore).reversed())
                    .map(this::rankedChunkToDocument)
                    .collect(Collectors.toList());

            // No-op (returns fusedDocuments capped at topK, same as before) when reranking is disabled.
            List<Document> documents = crossEncoderReranker.rerank(query, fusedDocuments, topK);

            observation
                    .lowCardinalityKeyValue("retrieval_outcome", "success")
                    .lowCardinalityKeyValue("sources_available", "both")
                    .lowCardinalityKeyValue("dense_hit_count", bucketize(denseHits.size()))
                    .lowCardinalityKeyValue("sparse_hit_count", bucketize(sparseHits.size()));

            hybridRetrievalSuccess.increment();
            return new DocumentBundle(documents);
        }

        // CASE 2: Dense succeeded, Sparse failed → use dense only
        if (denseResult.isSuccess() && !sparseResult.isSuccess()) {
            log.warn("Sparse search failed, using dense-only results: groupId={}, error={}",
                    groupId, sparseResult.getError().getMessage());

            List<VectorHit> denseHits = (List<VectorHit>) denseResult.getResults();
            denseHitsSummary.record(denseHits.size());

            List<Document> denseDocuments = denseHits.stream()
                    .sorted(Comparator.comparingDouble(VectorHit::score).reversed())
                    .map(this::vectorHitToDocument)
                    .collect(Collectors.toList());

            // No-op (returns denseDocuments capped at topK, same as before) when reranking is disabled.
            List<Document> documents = crossEncoderReranker.rerank(query, denseDocuments, topK);

            observation
                    .lowCardinalityKeyValue("retrieval_outcome", "degraded")
                    .lowCardinalityKeyValue("sources_available", "dense_only")
                    .lowCardinalityKeyValue("dense_hit_count", bucketize(denseHits.size()));

            sparseRetrievalFailures.increment();
            degradedRetrievalSuccess.increment();
            return new DocumentBundle(documents);
        }

        // CASE 3: Sparse succeeded, Dense failed → use sparse only (fallback)
        if (!denseResult.isSuccess() && sparseResult.isSuccess()) {
            log.warn("Dense search failed, using sparse-only results: groupId={}, error={}",
                    groupId, denseResult.getError().getMessage());

            List<KeywordHit> sparseHits = (List<KeywordHit>) sparseResult.getResults();
            sparseHitsSummary.record(sparseHits.size());

            List<Document> sparseDocuments = sparseHits.stream()
                    .sorted(Comparator.comparingDouble(KeywordHit::normalizedScore).reversed())
                    .map(this::keywordHitToDocument)
                    .collect(Collectors.toList());

            // No-op (returns sparseDocuments capped at topK, same as before) when reranking is disabled.
            List<Document> documents = crossEncoderReranker.rerank(query, sparseDocuments, topK);

            observation
                    .lowCardinalityKeyValue("retrieval_outcome", "degraded")
                    .lowCardinalityKeyValue("sources_available", "sparse_only")
                    .lowCardinalityKeyValue("sparse_hit_count", bucketize(sparseHits.size()));

            denseRetrievalFailures.increment();
            degradedRetrievalSuccess.increment();
            return new DocumentBundle(documents);
        }

        // CASE 4: Both failed → throw exception (data integrity issue)
        log.error("Both dense and sparse searches failed for groupId={}: dense={}, sparse={}",
                groupId, denseResult.getError().getMessage(), sparseResult.getError().getMessage());

        observation
                .lowCardinalityKeyValue("retrieval_outcome", "failed")
                .lowCardinalityKeyValue("sources_available", "none");

        throw new RetrievalException(
                denseResult.getError(),
                sparseResult.getError()
        );
    }

    /**
     * Fuses dense and sparse ranked lists using Reciprocal Rank Fusion (RRF).
     * Combines rankings from both sources into a single unified score.
     */
    private List<RankedChunk> fuseWithReciprocalRankFusion(
            List<VectorHit> denseHits,
            List<KeywordHit> sparseHits,
            int rrfK) {

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

    /**
     * Converts RankedChunk (from RRF fusion) to Spring AI Document.
     */
    private Document rankedChunkToDocument(RankedChunk chunk) {
        return new Document(
                chunk.content(),
                Map.of(
                        "chunkId", chunk.chunkId(),
                        "documentId", chunk.documentId(),
                        "chunkNumber", chunk.chunkNumber(),
                        "similarity", chunk.rrfScore(),
                        "source", "hybrid"
                )
        );
    }

    /**
     * Converts VectorHit (dense-only) to Spring AI Document.
     */
    private Document vectorHitToDocument(VectorHit hit) {
        return new Document(
                hit.content(),
                Map.of(
                        "chunkId", hit.chunkId(),
                        "documentId", hit.documentId(),
                        "chunkNumber", hit.chunkNumber(),
                        "similarity", hit.score(),
                        "source", "dense"
                )
        );
    }

    /**
     * Converts KeywordHit (sparse-only) to Spring AI Document.
     */
    private Document keywordHitToDocument(KeywordHit hit) {
        return new Document(
                hit.chunkText(),
                Map.of(
                        "chunkId", hit.chunkId(),
                        "documentId", hit.documentId(),
                        "chunkNumber", hit.chunkIndex(),
                        "similarity", hit.normalizedScore(),
                        "source", "sparse"
                )
        );
    }

    /**
     * Bucketizes hit counts for low-cardinality observation tag.
     */
    private String bucketize(int count) {
        if (count < 10) return "<10";
        if (count < 50) return "10-50";
        if (count < 100) return "50-100";
        return ">100";
    }

    /**
     * Accumulates RRF score contributions for a single chunk across dense and sparse rankings.
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