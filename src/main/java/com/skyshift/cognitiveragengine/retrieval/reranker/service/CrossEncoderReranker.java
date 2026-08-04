package com.skyshift.cognitiveragengine.retrieval.reranker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.qa.config.RetrievalProperties;
import com.skyshift.cognitiveragengine.retrieval.reranker.model.ScoredDocument;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Reranks RRF-fused candidates against the query using {@link CrossEncoderService}, scoring
 * candidates in parallel with a bounded timeout. Degrades gracefully: if reranking is disabled,
 * a single candidate fails to score, or the batch doesn't finish in time, callers get back the
 * original (RRF-ordered) candidates capped at topN rather than an error.
 */
@Slf4j
@Service
public class CrossEncoderReranker {

    private static final String RERANK_SCORE_METADATA_KEY = "rerankScore";
    private static final String OBSERVATION_NAME = "rag.reranking";

    private final CrossEncoderService crossEncoderService;
    private final RetrievalProperties.Reranker properties;
    private final ExecutorService executor;
    private final ObservationRegistry observationRegistry;
    private final ObjectMapper objectMapper;

    public CrossEncoderReranker(
            CrossEncoderService crossEncoderService,
            RetrievalProperties retrievalProperties,
            ObservationRegistry observationRegistry,
            ObjectMapper objectMapper) {
        this.crossEncoderService = crossEncoderService;
        this.properties = retrievalProperties.getReranker();
        this.executor = ContextExecutorService.wrap(Executors.newFixedThreadPool(properties.getParallelism()));
        this.observationRegistry = observationRegistry;
        this.objectMapper = objectMapper;
    }

    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        log.info("Reranking {} candidates for query='{}' with topN={}", candidates.size(), query, topN);

        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("reranker_model", properties.getModelPath())
                .lowCardinalityKeyValue("candidate_count", bucketize(candidates.size()))
                .highCardinalityKeyValue("top_n", String.valueOf(topN));

        return observation.observe(() -> doRerank(observation, query, candidates, topN));
    }

    private List<Document> doRerank(Observation observation, String query, List<Document> candidates, int topN) {
        if (!properties.isEnabled() || candidates.isEmpty()) {
            observation.lowCardinalityKeyValue("outcome", !properties.isEnabled() ? "disabled" : "empty");
            return capToTopN(candidates, topN);
        }

        List<Document> pool = candidates.size() > properties.getMaxCandidates()
                ? candidates.subList(0, properties.getMaxCandidates())
                : candidates;

        Map<Object, Integer> originalRankByChunkId = new LinkedHashMap<>();
        for (int i = 0; i < pool.size(); i++) {
            originalRankByChunkId.put(pool.get(i).getMetadata().get("chunkId"), i + 1);
        }

        List<CompletableFuture<ScoredDocument>> futures = pool.stream()
                .map(document -> CompletableFuture
                        .supplyAsync(() -> crossEncoderService.score(query, document.getText()), executor)
                        .handle((score, error) -> {
                            if (error != null) {
                                log.warn("Cross-encoder scoring failed for chunkId={}: {}",
                                        document.getMetadata().get("chunkId"), error.getMessage());
                                return new ScoredDocument(document, Double.NEGATIVE_INFINITY);
                            }
                            return new ScoredDocument(document, score);
                        }))
                .collect(Collectors.toList());

        try {
            List<ScoredDocument> scored = CompletableFuture
                    .allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()))
                    .get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);

            List<ScoredDocument> topScored = scored.stream()
                    .filter(scoredDocument -> scoredDocument.score() > Double.NEGATIVE_INFINITY)
                    .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                    .limit(topN)
                    .collect(Collectors.toList());

            observation.lowCardinalityKeyValue("outcome", "scored");
            observation.highCardinalityKeyValue("rank_shift", serializeRankShift(topScored, originalRankByChunkId));

            return topScored.stream()
                    .map(this::attachRerankScore)
                    .collect(Collectors.toList());
        } catch (TimeoutException e) {
            log.warn("Cross-encoder reranking timed out after {}ms for {} candidates; falling back to RRF order",
                    properties.getTimeoutMs(), pool.size());
            observation.lowCardinalityKeyValue("outcome", "timeout");
            return capToTopN(candidates, topN);
        } catch (Exception e) {
            log.warn("Cross-encoder reranking failed; falling back to RRF order: {}", e.getMessage(), e);
            observation.lowCardinalityKeyValue("outcome", "error");
            return capToTopN(candidates, topN);
        }
    }

    /**
     * Original (pre-rerank) vs. new (post-rerank) position per returned chunk, so retrieval
     * quality can be inspected in the trace - e.g. spotting when reranking demotes a
     * high-RRF-rank chunk the LLM never sees.
     */
    private String serializeRankShift(List<ScoredDocument> topScored, Map<Object, Integer> originalRankByChunkId) {
        try {
            List<Map<String, Object>> details = new ArrayList<>();
            for (int i = 0; i < topScored.size(); i++) {
                ScoredDocument scoredDocument = topScored.get(i);
                Object chunkId = scoredDocument.document().getMetadata().get("chunkId");
                details.add(Map.of(
                        "chunkId", String.valueOf(chunkId),
                        "originalRank", originalRankByChunkId.getOrDefault(chunkId, -1),
                        "newRank", i + 1,
                        "score", scoredDocument.score()
                ));
            }
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.debug("Failed to serialize rerank rank-shift for tracing: {}", e.getMessage());
            return "[]";
        }
    }

    private String bucketize(int count) {
        if (count < 10) return "<10";
        if (count < 30) return "10-30";
        if (count < 60) return "30-60";
        return ">60";
    }

    private Document attachRerankScore(ScoredDocument scoredDocument) {
        Document document = scoredDocument.document();
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put(RERANK_SCORE_METADATA_KEY, scoredDocument.score());
        return new Document(document.getText(), metadata);
    }

    private List<Document> capToTopN(List<Document> documents, int topN) {
        return documents.stream().limit(topN).collect(Collectors.toList());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}