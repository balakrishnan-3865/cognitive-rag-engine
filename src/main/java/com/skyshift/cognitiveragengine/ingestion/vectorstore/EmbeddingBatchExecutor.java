package com.skyshift.cognitiveragengine.ingestion.vectorstore;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.ingestion.exception.EmbeddingCircuitBreakerOpenException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
     * Circuit Breaker: opens once recent batches fail past the configured threshold, so a
     * struggling pgVector isn't hammered by every subsequent batch's full retry cycle.
     * Configuration: application.yaml resilience4j.retry.instances.embedding-batch,
     * resilience4j.circuitbreaker.instances.embedding-batch
     * <p>
     * Extracted into its own bean so {@code @Retry}/{@code @CircuitBreaker}/{@code @Transactional}
     * are applied via the Spring AOP proxy. Called via self-invocation from within the same class,
     * these annotations would be silently skipped.
     */
    @CircuitBreaker(name = "embedding-batch", fallbackMethod = "handleBatchEmbeddingFailure")
    @Retry(name = "embedding-batch")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void embedBatchWithRetry(List<Document> batch, int batchNum, int totalBatches, Long documentId) {
        try {
            vectorStore.add(batch);
        } catch (RuntimeException e) {
            // Rethrown as-is (not wrapped) so its real type - e.g. ResourceAccessException on a
            // dropped connection to the embedding endpoint - reaches @Retry's retryExceptions
            // matching. Wrapping in a generic RuntimeException would make it match nothing in
            // that list, silently disabling retry for every failure.
            log.warn("pgVector batch embedding failed (will retry): batch {}/{}, documentId={}, chunkCount={}",
                    batchNum + 1, totalBatches, documentId, batch.size());
            throw e;
        }
    }

    /**
     * Fallback: Called when either @CircuitBreaker rejects the call (breaker OPEN) or @Retry
     * exhausts max attempts for a batch. Both trigger a compensating rollback at orchestrator
     * level, but are reported as distinct exceptions since they mean different things: a breaker
     * rejection means pgVector is presumed unhealthy and this batch was never attempted, while a
     * retry-exhaustion means this specific batch was attempted and failed every time.
     * <p>
     * Method signature must match embedBatchWithRetry() plus Throwable parameter.
     */
    private void handleBatchEmbeddingFailure(
            List<Document> batch, int batchNum, int totalBatches, Long documentId, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.error("pgVector batch embedding SKIPPED: circuit breaker OPEN, batch {}/{}, " +
                     "documentId={}, chunkCount={}. Batch will be rolled back at document level.",
                     batchNum + 1, totalBatches, documentId, batch.size());

            throw new EmbeddingCircuitBreakerOpenException(
                "Batch " + (batchNum + 1) + " embedding skipped: circuit breaker open: " + t.getMessage(), t);
        }

        log.error("pgVector batch embedding PERMANENTLY FAILED after max retry attempts: batch {}/{}, " +
                 "documentId={}, chunkCount={}. Batch will be rolled back at document level.",
                 batchNum + 1, totalBatches, documentId, batch.size());

        throw new BusinessException(
            "Batch " + (batchNum + 1) + " embedding failed after max retry attempts: " + t.getMessage(), t);
    }
}