package com.skyshift.cognitiveragengine.ingestion.vectorstore;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class EmbeddingBatchExecutor {

    private final VectorStore vectorStore;

    public EmbeddingBatchExecutor(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Embeds a single batch with per-batch retry and exponential backoff.
     * Each batch gets its own transaction (REQUIRES_NEW) for isolation.
     * If batch fails after max retries, fallback throws BusinessException.
     * <p>
     * Retry: 3 max attempts with exponential backoff (100ms initial, 2x multiplier)
     * Configuration: application.yaml resilience4j.retry.instances.embedding-batch
     * <p>
     * Extracted into its own bean so {@code @Retry}/{@code @Transactional} are applied via the
     * Spring AOP proxy. Called via self-invocation from within the same class, both annotations
     * would be silently skipped.
     */
    @Retry(name = "embedding-batch", fallbackMethod = "handleBatchEmbeddingFailure")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void embedBatchWithRetry(List<Document> batch, int batchNum, int totalBatches, Long documentId) {
        try {
            vectorStore.add(batch);
        } catch (Exception e) {
            log.warn("pgVector batch embedding failed (will retry): batch {}/{}, documentId={}, chunkCount={}",
                    batchNum + 1, totalBatches, documentId, batch.size());
            throw new RuntimeException(
                "Batch " + batchNum + " embedding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fallback: Called when @Retry exhausts max attempts for a batch.
     * Throws BusinessException to trigger compensating rollback at orchestrator level.
     * <p>
     * Method signature must match embedBatchWithRetry() plus Throwable parameter.
     */
    private void handleBatchEmbeddingFailure(
            List<Document> batch, int batchNum, int totalBatches, Long documentId, Throwable t) {
        log.error("pgVector batch embedding PERMANENTLY FAILED after max retry attempts: batch {}/{}, " +
                 "documentId={}, chunkCount={}. Batch will be rolled back at document level.",
                 batchNum + 1, totalBatches, documentId, batch.size());

        throw new BusinessException(
            "Batch " + (batchNum + 1) + " embedding failed after max retry attempts: " + t.getMessage(), t);
    }
}