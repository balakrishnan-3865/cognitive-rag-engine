package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.ingestion.event.DocumentChunksCreatedEvent;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentIngestionRunMapper;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.IngestionRunStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 7: the streaming-era batch/cutover boundary (Section 2). Shadow-batch flushes are
 * deliberately NOT wrapped in a transaction here — each flush is its own short, independently
 * committing unit (Section 2: "No outer transaction — each batch flush is its own short
 * transaction"), made safe to retry by the {@code ON CONFLICT DO NOTHING} constraint (Section 8).
 * {@link #cutover} is the one place that needs atomicity, since it flips visibility and publishes
 * the completion event together.
 */
@Slf4j
@Service
public class DocumentChunkBatchService {

    // Keeps each insert well under Postgres's bind-parameter limit per statement.
    private static final int POSTGRES_PARAMETER_LIMIT = 32_767;
    private static final int INSERT_BATCH_PARAMETER_COUNT = 9;
    private static final int MAX_INSERT_BATCH_SIZE = POSTGRES_PARAMETER_LIMIT / INSERT_BATCH_PARAMETER_COUNT;

    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentIngestionRunMapper documentIngestionRunMapper;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentChunkBatchService(
            DocumentChunkMapper documentChunkMapper,
            DocumentIngestionRunMapper documentIngestionRunMapper,
            ApplicationEventPublisher eventPublisher) {
        this.documentChunkMapper = documentChunkMapper;
        this.documentIngestionRunMapper = documentIngestionRunMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Flushes shadow rows (already tagged with {@code ingestionRunId}/{@code isCurrent=false} by
     * the caller) in Postgres-parameter-safe batches. No delete, no run-status change, no
     * visibility flip — those only ever happen once, at {@link #cutover}.
     */
    public void insertShadowChunks(List<DocumentChunkEntity> chunks) {
        int total = chunks.size();
        log.info("Flushing {} shadow chunk rows", total);

        if (total <= MAX_INSERT_BATCH_SIZE) {
            documentChunkMapper.batchInsertChunks(chunks);
            return;
        }

        for (int start = 0; start < total; start += MAX_INSERT_BATCH_SIZE) {
            int end = Math.min(start + MAX_INSERT_BATCH_SIZE, total);
            documentChunkMapper.batchInsertChunks(chunks.subList(start, end));
        }
    }

    /**
     * Atomic cutover (Section 2/4): retire the previous current chunk set, promote this run's
     * shadow rows to current, mark the run complete, and publish
     * {@link DocumentChunksCreatedEvent} — all inside one short transaction, so the
     * {@code @TransactionalEventListener(AFTER_COMMIT)} downstream only ever fires once this
     * commits, and only once chunks are actually visible.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void cutover(Long documentId, Long groupId, Long ingestionRunId) {
        int retired = documentChunkMapper.retireCurrentChunks(documentId, groupId);
        int promoted = documentChunkMapper.promoteRunChunks(ingestionRunId);
        documentIngestionRunMapper.updateStatus(ingestionRunId, IngestionRunStatus.CUTOVER_COMPLETE.name());

        log.info("Cutover complete: documentId={}, ingestionRunId={}, retired={}, promoted={}",
            documentId, ingestionRunId, retired, promoted);

        eventPublisher.publishEvent(new DocumentChunksCreatedEvent(documentId, groupId));
    }
}
