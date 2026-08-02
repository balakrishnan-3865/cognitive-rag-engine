package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.ingestion.exception.NoChunksFoundException;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DocumentStatus;
import com.skyshift.cognitiveragengine.ingestion.vectorstore.VectorIngestionService;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.service.ElasticsearchChunkIndexService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class ChunkVectorIngestionOrchestrator {

    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentMapper documentMapper;
    private final VectorIngestionService vectorIngestionService;
    private final ElasticsearchChunkIndexService elasticsearchChunkIndexService;

    public ChunkVectorIngestionOrchestrator(
            DocumentChunkMapper documentChunkMapper,
            DocumentMapper documentMapper,
            VectorIngestionService vectorIngestionService,
            ElasticsearchChunkIndexService elasticsearchChunkIndexService) {
        this.documentChunkMapper = documentChunkMapper;
        this.documentMapper = documentMapper;
        this.vectorIngestionService = vectorIngestionService;
        this.elasticsearchChunkIndexService = elasticsearchChunkIndexService;
    }

    public void ingestVectorsAndIndexChunks(Long documentId, Long groupId) {
        if (documentId == null || groupId == null) {
            log.error("Invalid parameters: documentId={}, groupId={}", documentId, groupId);
            throw new BusinessException("Document ID and Group ID must not be null");
        }

        log.info("Starting vector ingestion and indexing for documentId={}, groupId={}", documentId, groupId);

        validateDocumentExists(documentId);

        if (!acquireIngestionLock(documentId)) {
            log.warn("Document is already being ingested by another instance: documentId={}", documentId);
            throw new BusinessException(
                "Document is currently being ingested by another instance. " +
                "Please retry after the current ingestion completes.");
        }

        try {
            List<DocumentChunkEntity> chunks = fetchDocumentChunks(documentId, groupId);
            embedAndStoreVectors(documentId, chunks);
            indexInElasticsearch(documentId, chunks);

            markDocumentAsReady(documentId);
            log.info("Vector ingestion and indexing completed successfully for documentId={}", documentId);

        } catch (NoChunksFoundException exception) {
            log.warn("No chunks found for document: documentId={}", documentId, exception);
            documentMapper.updateStatusAndReason(documentId,
                DocumentStatus.NO_CHUNKS_FOUND.name(),
                "Ingestion skipped: no document chunks available");

        } catch (Exception exception) {
            log.error("Vector ingestion and indexing failed for documentId={}", documentId, exception);
            documentMapper.updateStatusAndReason(documentId,
                DocumentStatus.FAILED.name(),
                "Vector/Elasticsearch Stage: " + exception.getMessage());

        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void embedAndStoreVectors(Long documentId, List<DocumentChunkEntity> chunks) {
        try {
            vectorIngestionService.embedAndStoreDocumentChunks(documentId, chunks);
            log.info("Vector embedding completed for documentId={}", documentId);

        } catch (NoChunksFoundException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Vector embedding failed for documentId={}", documentId, exception);
            throw new BusinessException("Vector embedding failed: " + exception.getMessage(), exception);
        }
    }

    @NotNull
    private List<DocumentChunkEntity> fetchDocumentChunks(Long documentId, Long groupId) {
        List<DocumentChunkEntity> chunks = documentChunkMapper.selectByDocumentIdAndGroupId(documentId, groupId);

        if (chunks.isEmpty()) {
            throw new NoChunksFoundException(
                    "No chunks found for documentId=" + documentId);
        }
        log.debug("Fetched {} chunks for documentId={}", chunks.size(), documentId);
        return chunks;
    }

    private void indexInElasticsearch(Long documentId, List<DocumentChunkEntity> chunks) {
        try {
            String fileName = getDocumentFileName(documentId);
            elasticsearchChunkIndexService.indexChunks(documentId, fileName, chunks);
            log.info("Elasticsearch indexing completed for {} chunks", chunks.size());

        } catch (NoChunksFoundException exception) {
            throw exception;

        } catch (Exception exception) {
            log.error("Elasticsearch indexing failed for documentId={}. Triggering compensating rollback...",
                     documentId, exception);

            try {
                vectorIngestionService.deleteExistingEmbeddings(documentId);
                log.info("Compensating rollback successful: deleted embeddings for documentId={} " +
                        "to maintain consistency with Elasticsearch failure", documentId);
            } catch (Exception rollbackError) {
                log.error("CRITICAL: Compensating rollback FAILED for documentId={}. " +
                         "pgVector has embeddings but Elasticsearch indexing failed. Manual cleanup required.",
                         documentId, rollbackError);
                throw new BusinessException(
                    "Elasticsearch indexing failed AND compensating rollback failed. " +
                    "pgVector has orphaned embeddings. Manual cleanup required for documentId: " + documentId,
                    rollbackError);
            }

            throw new BusinessException("Elasticsearch indexing failed: " + exception.getMessage(), exception);
        }
    }

    private String getDocumentFileName(Long documentId) {
        DocumentEntity doc = documentMapper.selectById(documentId);
        return doc != null ? doc.getFileName() : "unknown";
    }

    private void markDocumentAsReady(Long documentId) {
        log.info("Marking document as READY: documentId={}", documentId);
        documentMapper.updateStatus(documentId, DocumentStatus.READY.name());
    }

    private void validateDocumentExists(Long documentId) {
        DocumentEntity document = documentMapper.selectById(documentId);

        if (document == null) {
            log.error("Document not found: documentId={}", documentId);
            throw new BusinessException("Document not found: documentId=" + documentId);
        }

        if (Boolean.TRUE.equals(document.getDeleted())) {
            log.error("Document is deleted: documentId={}", documentId);
            throw new BusinessException("Document is deleted: documentId=" + documentId);
        }
    }

    /**
     * Acquires an ingestion lock by atomically transitioning document status.
     * Only succeeds if document is currently in PROCESSING state (another instance guard).
     * Returns true if lock acquired (transition successful), false if already being ingested.
     *
     * Status transition: PROCESSING → INJECTING (idempotency guard for distributed systems)
     * If transition fails (returns 0 rows), another instance is already working on this document.
     */
    private boolean acquireIngestionLock(Long documentId) {
        int rowsUpdated = documentMapper.updateStatusFromTo(
            documentId,
            DocumentStatus.PROCESSING.name(),
            DocumentStatus.INJECTING.name()
        );

        if (rowsUpdated > 0) {
            log.info("Ingestion lock acquired: documentId={} PROCESSING → INJECTING", documentId);
            return true;
        } else {
            log.warn("Ingestion lock NOT acquired: documentId={} is not in PROCESSING state", documentId);
            return false;
        }
    }
}