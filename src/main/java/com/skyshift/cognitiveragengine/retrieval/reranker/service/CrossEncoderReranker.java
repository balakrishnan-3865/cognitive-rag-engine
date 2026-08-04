package com.skyshift.cognitiveragengine.retrieval.reranker.service;

import com.skyshift.cognitiveragengine.qa.config.RetrievalProperties;
import com.skyshift.cognitiveragengine.retrieval.reranker.model.ScoredDocument;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
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

    private final CrossEncoderService crossEncoderService;
    private final RetrievalProperties.Reranker properties;
    private final ExecutorService executor;

    public CrossEncoderReranker(CrossEncoderService crossEncoderService, RetrievalProperties retrievalProperties) {
        this.crossEncoderService = crossEncoderService;
        this.properties = retrievalProperties.getReranker();
        this.executor = Executors.newFixedThreadPool(properties.getParallelism());
    }

    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (!properties.isEnabled() || candidates.isEmpty()) {
            return capToTopN(candidates, topN);
        }

        List<Document> pool = candidates.size() > properties.getMaxCandidates()
                ? candidates.subList(0, properties.getMaxCandidates())
                : candidates;

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

            return scored.stream()
                    .filter(scoredDocument -> scoredDocument.score() > Double.NEGATIVE_INFINITY)
                    .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                    .limit(topN)
                    .map(this::attachRerankScore)
                    .collect(Collectors.toList());
        } catch (TimeoutException e) {
            log.warn("Cross-encoder reranking timed out after {}ms for {} candidates; falling back to RRF order",
                    properties.getTimeoutMs(), pool.size());
            return capToTopN(candidates, topN);
        } catch (Exception e) {
            log.warn("Cross-encoder reranking failed; falling back to RRF order: {}", e.getMessage(), e);
            return capToTopN(candidates, topN);
        }
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