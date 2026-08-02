package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DocumentStatus;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.service.ElasticsearchChunkIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class IngestionRecoveryScheduler {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final VectorStore vectorStore;
    private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;
    private final long recoveryIntervalSeconds;

    public IngestionRecoveryScheduler(
            DocumentMapper documentMapper,
            DocumentChunkMapper documentChunkMapper,
            VectorStore vectorStore,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService,
            @Value("${app.ingestion.recovery.interval-seconds:3600}") long recoveryIntervalSeconds) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.vectorStore = vectorStore;
        this.elasticsearchChunkIndexService = elasticsearchChunkIndexService;
        this.recoveryIntervalSeconds = recoveryIntervalSeconds;
    }

    /**
     * Reconciles documents stuck in INJECTING state.
     * Runs at configurable interval (default 1 hour).
     *
     * Recovery logic:
     * 1. Find documents in INJECTING status
     * 2. For each document, verify data integrity:
     *    - Count chunks in database
     *    - Count vectors in pgVector (by documentId filter)
     *    - Count documents in Elasticsearch (by documentId filter)
     * 3. If all counts match → update status to READY (data is complete)
     * 4. If counts differ → update status to FAILED (data inconsistency detected)
     *
     * Use case: Recovers documents where ingestion succeeded but status update failed.
     */
    @Scheduled(fixedRateString = "${app.ingestion.recovery.interval-seconds:3600}", timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    public void reconcileIngestingDocuments() {
        log.info("Starting ingestion recovery reconciliation");

        List<DocumentEntity> ingestingDocuments = documentMapper.findByStatus(DocumentStatus.INJECTING.name());

        if (ingestingDocuments.isEmpty()) {
            log.debug("No documents in INJECTING state");
            return;
        }

        log.info("Found {} documents in INJECTING state", ingestingDocuments.size());

        for (DocumentEntity document : ingestingDocuments) {
            reconcileDocument(document);
        }
    }

    /**
     * Reconciles a single document: verifies data completeness and updates status.
     */
    private void reconcileDocument(DocumentEntity document) {
        Long documentId = document.getId();

        try {
            int expectedChunkCount = documentChunkMapper.countByDocumentId(documentId);
            int pgvectorCount = countVectorsInPgVector(documentId);
            int elasticsearchCount = countDocumentsInElasticsearch(documentId);

            log.debug("Reconciliation check: documentId={}, expected={}, pgvector={}, elasticsearch={}",
                     documentId, expectedChunkCount, pgvectorCount, elasticsearchCount);

            if (expectedChunkCount > 0 &&
                pgvectorCount == expectedChunkCount &&
                elasticsearchCount == expectedChunkCount) {
                // Data is complete, mark READY
                markDocumentAsReady(documentId);
                log.info("Reconciliation SUCCESS: Document marked READY after data verification: documentId={}, chunkCount={}",
                         documentId, expectedChunkCount);

            } else if (expectedChunkCount == 0) {
                // No chunks expected but document was in INJECTING
                log.warn("Reconciliation SKIPPED: Document has no chunks but was in INJECTING state: documentId={}",
                         documentId);

            } else {
                // Data inconsistency detected
                String reason = String.format(
                    "Chunk count mismatch detected during recovery. Expected: %d, pgVector: %d, Elasticsearch: %d",
                    expectedChunkCount, pgvectorCount, elasticsearchCount);

                markDocumentAsFailed(documentId, reason);
                log.error("Reconciliation FAILED: Data inconsistency for documentId={}. {}",
                         documentId, reason);
            }

        } catch (Exception error) {
            log.error("Reconciliation ERROR: Failed to reconcile document: documentId={}. " +
                     "Will retry on next scheduled run.",
                     documentId, error);
        }
    }

    /**
     * Counts vectors in pgVector for the given documentId.
     * Executes a native SQL query against the vector_store table using JdbcTemplate
     * extracted from the underlying VectorStore's native client.
     */
    private int countVectorsInPgVector(Long documentId) {
        try {
            Objects.requireNonNull(documentId, "documentId cannot be null");

            JdbcTemplate jdbcTemplate = (JdbcTemplate) vectorStore.getNativeClient()
                    .orElseThrow(() -> new IllegalStateException("Native JDBC client not accessible from VectorStore"));

            String sql = """
                SELECT COUNT(*)
                FROM vector_store
                WHERE (metadata->'documentId')::int = ?
                """;

            Long count = jdbcTemplate.queryForObject(
                    sql,
                    Long.class,
                    documentId.intValue()
            );

            return count != null ? count.intValue() : 0;

        } catch (Exception e) {
            log.warn("Failed to count vectors in pgVector for documentId={}: {}",
                     documentId, e.getMessage());
            return -1;
        }
    }

    /**
     * Counts documents in Elasticsearch for the given documentId.
     */
    private int countDocumentsInElasticsearch(Long documentId) {
        try {
            // Elasticsearch service should provide a count method
            // For now, we assume the service handles this internally
            return elasticsearchChunkIndexService.countByDocumentId(documentId);

        } catch (IOException e) {
            log.warn("Failed to count documents in Elasticsearch for documentId={}: {}",
                     documentId, e.getMessage());
            return -1;
        }
    }

    /**
     * Marks document as READY status.
     */
    private void markDocumentAsReady(Long documentId) {
        try {
            documentMapper.updateStatus(documentId, DocumentStatus.READY.name());
        } catch (Exception e) {
            log.error("Failed to update document status to READY: documentId={}", documentId, e);
            throw new RuntimeException("Status update failed for documentId: " + documentId, e);
        }
    }

    /**
     * Marks document as FAILED status with reason.
     */
    private void markDocumentAsFailed(Long documentId, String reason) {
        try {
            documentMapper.updateStatusAndReason(documentId, DocumentStatus.FAILED.name(), reason);
        } catch (Exception e) {
            log.error("Failed to update document status to FAILED: documentId={}", documentId, e);
        }
    }
}