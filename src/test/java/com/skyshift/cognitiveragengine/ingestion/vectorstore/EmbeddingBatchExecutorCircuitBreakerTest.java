package com.skyshift.cognitiveragengine.ingestion.vectorstore;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.ingestion.exception.EmbeddingCircuitBreakerOpenException;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Proxy-level tests: exercises the real Spring AOP proxy for {@link EmbeddingBatchExecutor} so
 * both {@code @Retry} and {@code @CircuitBreaker} aspects actually run (unlike tests that do
 * {@code new EmbeddingBatchExecutor(vectorStore)}, which bypasses both annotations entirely).
 * <p>
 * Test-only sliding window is configured in application-test.yaml (window=4, minCalls=4,
 * threshold=50%, waitDurationInOpenState=500ms) so state transitions happen fast and
 * deterministically without needing production-sized call volumes.
 * <p>
 * [ASSUMPTION] {@code @CircuitBreaker(name = "embedding-batch", fallbackMethod = "handleBatchEmbeddingFailure")}
 * has NOT been added to EmbeddingBatchExecutor yet (pending Step 6). These tests are written
 * RED-first per the confirmed TDD plan and are expected to fail to compile/pass until that
 * annotation and the fallback dispatch logic (distinguishing CallNotPermittedException from a
 * genuine retry-exhaustion) are implemented.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("EmbeddingBatchExecutor Circuit Breaker Tests")
class EmbeddingBatchExecutorCircuitBreakerTest {

    @MockitoBean
    private VectorStore vectorStore;

    @Autowired
    private EmbeddingBatchExecutor embeddingBatchExecutor;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = circuitBreakerRegistry.circuitBreaker("embedding-batch");
        breaker.reset();
        reset(vectorStore);
    }

    @Test
    @DisplayName("Happy path: single successful call leaves breaker CLOSED")
    void testHappyPath_BreakerStaysClosed() {
        embeddingBatchExecutor.embedBatchWithRetry(sampleBatch(), 0, 1, 1L);

        verify(vectorStore, times(1)).add(anyList());
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    @DisplayName("Retry absorbs a transient failure; breaker counts the whole call as ONE success")
    void testRetryAbsorbsTransientFailure_BreakerRecordsSingleSuccess() {
        // ResourceAccessException (not a bare RuntimeException) because @Retry's retryExceptions
        // is an allow-list; only listed types are retried at all.
        AtomicInteger attempt = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (attempt.getAndIncrement() == 0) {
                throw new ResourceAccessException("transient pgVector failure");
            }
            return null;
        }).when(vectorStore).add(anyList());

        embeddingBatchExecutor.embedBatchWithRetry(sampleBatch(), 0, 1, 1L);

        // Retry attempted twice at the vectorStore level (1 failure + 1 success)...
        verify(vectorStore, times(2)).add(anyList());
        // ...but the breaker only sees ONE outcome for the whole retried call: a success.
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(1, breaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
    }

    @Test
    @DisplayName("Breaker opens after failure-rate threshold is crossed; subsequent call fails fast without hitting vectorStore")
    void testCircuitOpensAfterRepeatedFailures_FailsFastWithoutInvokingDownstream() {
        doThrow(new ResourceAccessException("pgVector down")).when(vectorStore).add(anyList());

        // window=4, minCalls=4, threshold=50% -> 4 fully-retried, fully-failed calls trips it open.
        for (int i = 0; i < 4; i++) {
            int batchNum = i;
            assertThrows(BusinessException.class, () ->
                    embeddingBatchExecutor.embedBatchWithRetry(sampleBatch(), batchNum, 4, 1L));
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        clearInvocations(vectorStore);

        // Next call must fail immediately via the breaker-open fallback path, WITHOUT
        // ever invoking vectorStore.add (that's the whole point of the breaker).
        assertThrows(EmbeddingCircuitBreakerOpenException.class, () ->
                embeddingBatchExecutor.embedBatchWithRetry(sampleBatch(), 5, 6, 1L));
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("OPEN -> HALF_OPEN -> CLOSED: breaker recovers once downstream calls succeed again")
    void testHalfOpenTransitionAndRecovery() throws InterruptedException {
        doThrow(new ResourceAccessException("pgVector down")).when(vectorStore).add(anyList());
        for (int i = 0; i < 4; i++) {
            int batchNum = i;
            assertThrows(BusinessException.class, () ->
                    embeddingBatchExecutor.embedBatchWithRetry(sampleBatch(), batchNum, 4, 1L));
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // waitDurationInOpenState is 500ms in the test profile; give it a safety margin.
        Thread.sleep(700);

        reset(vectorStore); // clear the doThrow stub; default mock behavior is a no-op success

        // permittedNumberOfCallsInHalfOpenState=2: two successful probe calls should close it.
        embeddingBatchExecutor.embedBatchWithRetry(sampleBatch(), 0, 2, 1L);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

        embeddingBatchExecutor.embedBatchWithRetry(sampleBatch(), 1, 2, 1L);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());

        verify(vectorStore, times(2)).add(anyList());
    }

    private List<Document> sampleBatch() {
        DocumentChunkEntity chunk = DocumentChunkEntity.builder()
                .id(1L)
                .documentId(1L)
                .groupId(1L)
                .chunkNumber(0)
                .chunkText("sample text")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        return List.of(new Document(chunk.getChunkText()));
    }
}
