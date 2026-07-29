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

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentChunksCreated(DocumentChunksCreatedEvent event) {
        log.info("DocumentChunksCreatedEvent received after transaction commit: documentId={}, groupId={}",
            event.documentId(), event.groupId());

        chunkVectorIngestionOrchestrator.ingestVectorsAndIndexChunks(event.documentId(), event.groupId());
    }
}