package com.skyshift.cognitiveragengine.retrieval.elasticsearch.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.exception.ElasticsearchCircuitBreakerOpenException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Proxy-level tests: exercises the real Spring AOP proxy for {@link ElasticsearchChunkIndexService}
 * so both {@code @Retry} and {@code @CircuitBreaker} aspects actually run. Calls
 * {@code processBulkIndexBatch} directly (it doesn't call {@code ensureIndexExists}), so a mocked
 * {@link ElasticsearchClient} is sufficient - no real Elasticsearch instance is needed.
 * <p>
 * Test-only sliding window is configured in application-test.yaml (window=4, minCalls=4,
 * threshold=50%, waitDurationInOpenState=500ms).
 * <p>
 * {@code answers = Answers.RETURNS_DEEP_STUBS} is required on the mock: Spring Data
 * Elasticsearch's autoconfiguration eagerly builds an {@code ElasticsearchTemplate} bean off
 * this client at context startup (unrelated to anything under test here), calling
 * {@code elasticsearchClient.transport().jsonpMapper()}. A default Mockito mock returns null
 * for unstubbed calls, so {@code transport()} would NPE before any test body runs; deep stubs
 * return further mocks instead.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ElasticsearchChunkIndexService Circuit Breaker Tests")
class ElasticsearchChunkIndexServiceCircuitBreakerTest {

    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private ElasticsearchChunkIndexService elasticsearchChunkIndexService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = circuitBreakerRegistry.circuitBreaker("elasticsearch-batch");
        breaker.reset();
        reset(elasticsearchClient);
    }

    @Test
    @DisplayName("Happy path: single successful bulk call leaves breaker CLOSED")
    void testHappyPath_BreakerStaysClosed() throws IOException {
        stubBulkSuccess();

        elasticsearchChunkIndexService.processBulkIndexBatch("file.pdf", sampleBatch(), 1, 1);

        verify(elasticsearchClient, times(1)).bulk(any(java.util.function.Function.class));
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    @DisplayName("Retry absorbs a transient failure; breaker counts the whole call as ONE success")
    void testRetryAbsorbsTransientFailure_BreakerRecordsSingleSuccess() throws IOException {
        AtomicInteger attempt = new AtomicInteger(0);
        BulkResponse ok = mock(BulkResponse.class);
        when(ok.errors()).thenReturn(false);
        when(elasticsearchClient.bulk(any(java.util.function.Function.class))).thenAnswer(invocation -> {
            if (attempt.getAndIncrement() == 0) {
                throw new IOException("transient ES failure");
            }
            return ok;
        });

        elasticsearchChunkIndexService.processBulkIndexBatch("file.pdf", sampleBatch(), 1, 1);

        verify(elasticsearchClient, times(2)).bulk(any(java.util.function.Function.class));
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(1, breaker.getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
    }

    @Test
    @DisplayName("Breaker opens after failure-rate threshold is crossed; subsequent call fails fast without hitting Elasticsearch")
    void testCircuitOpensAfterRepeatedFailures_FailsFastWithoutInvokingDownstream() throws IOException {
        when(elasticsearchClient.bulk(any(java.util.function.Function.class)))
                .thenThrow(new IOException("Elasticsearch down"));

        for (int i = 0; i < 4; i++) {
            int batchNum = i;
            assertThrows(IOException.class, () ->
                    elasticsearchChunkIndexService.processBulkIndexBatch("file.pdf", sampleBatch(), batchNum, 4));
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        clearInvocations(elasticsearchClient);

        assertThrows(ElasticsearchCircuitBreakerOpenException.class, () ->
                elasticsearchChunkIndexService.processBulkIndexBatch("file.pdf", sampleBatch(), 5, 6));
        verify(elasticsearchClient, never()).bulk(any(java.util.function.Function.class));
    }

    @Test
    @DisplayName("OPEN -> HALF_OPEN -> CLOSED: breaker recovers once Elasticsearch calls succeed again")
    void testHalfOpenTransitionAndRecovery() throws IOException, InterruptedException {
        when(elasticsearchClient.bulk(any(java.util.function.Function.class)))
                .thenThrow(new IOException("Elasticsearch down"));
        for (int i = 0; i < 4; i++) {
            int batchNum = i;
            assertThrows(IOException.class, () ->
                    elasticsearchChunkIndexService.processBulkIndexBatch("file.pdf", sampleBatch(), batchNum, 4));
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        Thread.sleep(700);

        clearInvocations(elasticsearchClient);
        stubBulkSuccess();

        elasticsearchChunkIndexService.processBulkIndexBatch("file.pdf", sampleBatch(), 0, 2);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

        elasticsearchChunkIndexService.processBulkIndexBatch("file.pdf", sampleBatch(), 1, 2);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());

        verify(elasticsearchClient, times(2)).bulk(any(java.util.function.Function.class));
    }

    private void stubBulkSuccess() throws IOException {
        BulkResponse ok = mock(BulkResponse.class);
        when(ok.errors()).thenReturn(false);
        when(elasticsearchClient.bulk(any(java.util.function.Function.class))).thenReturn(ok);
    }

    private List<DocumentChunkEntity> sampleBatch() {
        return List.of(DocumentChunkEntity.builder()
                .id(1L)
                .groupId(1L)
                .documentId(1L)
                .chunkNumber(0)
                .chunkText("sample text")
                .startPosition(0)
                .endPosition(11)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }
}
