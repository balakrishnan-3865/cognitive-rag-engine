package com.skyshift.cognitiveragengine.document.listener;

import com.skyshift.cognitiveragengine.document.event.DocumentUploadedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Async listener for document upload events.
 * Processes document ingestion tasks asynchronously after successful transaction commit.
 * Uses AFTER_COMMIT phase to ensure the event is only processed after database transaction succeeds.
 */
@Slf4j
@Component
public class DocumentIngestionAsyncListener {

    /**
     * Handles DocumentUploadedEvent asynchronously.
     * Triggered only after the document insertion transaction is successfully committed.
     *
     * @param event the document uploaded event containing documentId and groupId
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        log.info("Processing document ingestion: documentId={}, groupId={}",
            event.documentId(), event.groupId());

        try {
            // TODO: Implement document ingestion pipeline
            // Steps could include:
            // 1. Retrieve document metadata from database
            // 2. Read document from MinIO storage
            // 3. Extract text/content from document
            // 4. Chunk the content (for RAG)
            // 5. Generate embeddings
            // 6. Store in vector database (PgVector)
            // 7. Index in Elasticsearch
            // 8. Update document status to "PROCESSED"

            log.debug("Document ingestion queued: documentId={}", event.documentId());

        } catch (Exception e) {
            log.error("Failed to process document ingestion: documentId={}, groupId={}",
                event.documentId(), event.groupId(), e);
            // TODO: Implement error handling strategy (retry, dead-letter queue, etc.)
        }
    }
}