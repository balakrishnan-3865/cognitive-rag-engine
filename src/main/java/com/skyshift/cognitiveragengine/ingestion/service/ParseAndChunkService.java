package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.ingestion.event.DocumentChunksCreatedEvent;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DocumentStatus;
import com.skyshift.cognitiveragengine.ingestion.reader.DocumentIngestionDocumentReader;
import com.skyshift.cognitiveragengine.ingestion.parser.factory.DocumentParserFactory;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class ParseAndChunkService {

    private final DocumentMapper documentMapper;
    private final ObjectStorageService objectStorageService;
    private final DocumentParserFactory parserFactory;
    private final ChunkingService chunkingService;
    private final DocumentChunkBatchService documentChunkBatchService;
    private final ApplicationEventPublisher eventPublisher;

    public ParseAndChunkService(
            DocumentMapper documentMapper,
            ObjectStorageService objectStorageService,
            DocumentParserFactory parserFactory,
            ChunkingService chunkingService,
            DocumentChunkBatchService documentChunkBatchService,
            ApplicationEventPublisher eventPublisher) {
        this.documentMapper = documentMapper;
        this.objectStorageService = objectStorageService;
        this.parserFactory = parserFactory;
        this.chunkingService = chunkingService;
        this.documentChunkBatchService = documentChunkBatchService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void parseAndChunkDocument(Long documentId, Long groupId) {
        log.info("Starting parse and chunk: documentId={}, groupId={}", documentId, groupId);

        try {
            DocumentEntity doc = documentMapper.selectById(documentId);
            if (doc == null) {
                throw new IllegalArgumentException("Document not found: " + documentId);
            }

            if (!doc.getStatus().equals(DocumentStatus.PENDING.name())) {
                log.info("Document {} already in state {}, skipping", documentId, doc.getStatus());
                return;
            }

            documentMapper.updateStatus(documentId, DocumentStatus.PROCESSING.name());
            log.info("Marked document {} as PROCESSING", documentId);

            DocumentIngestionDocumentReader reader = new DocumentIngestionDocumentReader(
                objectStorageService,
                parserFactory,
                doc);

            List<Document> parsedDocuments = reader.get();
            log.info("Parsed {} document sections", parsedDocuments.size());

            if(parsedDocuments.isEmpty()) {
                throw new IllegalStateException("Parsed documents are empty or null for documentId=" + documentId);
            }

            List<DocumentChunkEntity> chunks = chunkingService.chunk(parsedDocuments, documentId, groupId);
            log.info("Created {} chunks from {} sections", chunks.size(), parsedDocuments.size());

            int insertedCount = documentChunkBatchService.batchInsertWithIdempotency(
                documentId, chunks);
            log.info("Inserted {} chunks into database", insertedCount);

            log.info("Parse and chunk complete for documentId={}, publishing vector ingestion event", documentId);
            eventPublisher.publishEvent(new DocumentChunksCreatedEvent(documentId, groupId));

        } catch (Exception e) {
            log.error("Parse and chunk failed for documentId={}", documentId, e);
            documentMapper.updateStatusAndReason(documentId,
                DocumentStatus.FAILED.name(),
                "Parse Stage: " + e.getMessage());
            throw e;
        }
    }
}