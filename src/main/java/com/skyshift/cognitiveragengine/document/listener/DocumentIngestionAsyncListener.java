package com.skyshift.cognitiveragengine.document.listener;

import com.skyshift.cognitiveragengine.document.event.DocumentUploadedEvent;
import com.skyshift.cognitiveragengine.ingestion.service.ParseAndChunkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class DocumentIngestionAsyncListener {

    private final ParseAndChunkService parseAndChunkService;

    public DocumentIngestionAsyncListener(ParseAndChunkService parseAndChunkService) {
        this.parseAndChunkService = parseAndChunkService;
    }

    @Async("ingestionVirtualExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        log.info("Processing document ingestion: documentId={}, groupId={}",
            event.documentId(), event.groupId());

        parseAndChunkService.parseAndChunkDocument(event.documentId(), event.groupId());

        log.debug("Document ingestion queued: documentId={}", event.documentId());
    }
}