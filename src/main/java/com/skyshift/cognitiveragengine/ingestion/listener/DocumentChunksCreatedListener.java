package com.skyshift.cognitiveragengine.ingestion.listener;

import com.skyshift.cognitiveragengine.ingestion.event.DocumentChunksCreatedEvent;
import com.skyshift.cognitiveragengine.ingestion.service.ChunkVectorIngestionOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class DocumentChunksCreatedListener {

    private final ChunkVectorIngestionOrchestrator chunkVectorIngestionOrchestrator;

    public DocumentChunksCreatedListener(ChunkVectorIngestionOrchestrator chunkVectorIngestionOrchestrator) {
        this.chunkVectorIngestionOrchestrator = chunkVectorIngestionOrchestrator;
    }

    /**
     * Processes document chunks ingestion asynchronously after chunk creation is committed.
     *
     * Concurrency & Idempotency Guarantees:
     * - One event per documentId: DocumentChunksCreatedEvent is published exactly once when chunks are saved
     * - @Async: Processed in thread pool, non-blocking
     * - Database-level lock: acquireIngestionLock() uses atomic conditional UPDATE (PROCESSING → INJECTING)
     *   preventing duplicate processing even if event delivered multiple times or in highly concurrent environments
     * - State-based locking: Status value itself is the lock; no explicit acquire/release needed
     *
     * Safe for: Distributed systems, high concurrency, event bus retries, pod/instance scaling
     *
     * Flow: Event → PROCESSING status → INJECTING status (lock acquired) → embedAndStore + index
     *       → READY/FAILED/NO_CHUNKS_FOUND status (lock released implicitly)
     *
     * Any retry or concurrent invocation will see final status and be rejected by WHERE clause in conditional update.
     */
    @Async("ingestionVirtualExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentChunksCreated(DocumentChunksCreatedEvent event) {
        log.info("DocumentChunksCreatedEvent received after transaction commit: documentId={}, groupId={}",
            event.documentId(), event.groupId());

        chunkVectorIngestionOrchestrator.ingestVectorsAndIndexChunks(event.documentId(), event.groupId());
    }
}