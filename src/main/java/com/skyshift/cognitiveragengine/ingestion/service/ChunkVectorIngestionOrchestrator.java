package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DocumentStatus;
import com.skyshift.cognitiveragengine.ingestion.vectorstore.VectorIngestionService;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.service.ElasticsearchChunkIndexService;
import lombok.extern.slf4j.Slf4j;
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
        log.info("Starting vector ingestion and indexing: documentId={}, groupId={}", documentId, groupId);

        try {
            embedAndStoreVectors(documentId, groupId);
            indexInElasticsearch(documentId, groupId);
            markDocumentAsReady(documentId);
            log.info("Vector ingestion and indexing completed successfully for documentId={}", documentId);
        } catch (Exception e) {
            log.error("Vector ingestion and indexing failed for documentId={}", documentId, e);
            documentMapper.updateStatusAndReason(documentId,
                DocumentStatus.FAILED.name(),
                "Vector/Elasticsearch Stage: " + e.getMessage());
        }
    }

    /**
     * TX2: Isolated transaction for vector embedding and storage.
     * Separate TX from ParseAndChunkService to allow independent retry of embedding.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void embedAndStoreVectors(Long documentId, Long groupId) {
        log.info("TX2 (REQUIRES_NEW): Starting vector embedding for documentId={}", documentId);

        try {
            List<DocumentChunkEntity> chunks = documentChunkMapper.selectByDocumentIdAndGroupId(documentId, groupId);

            if (chunks.isEmpty()) {
                log.warn("No chunks found for vector embedding: documentId={}", documentId);
                return;
            }

            log.info("Fetched {} chunks for vector ingestion", chunks.size());

            vectorIngestionService.ingestDocumentChunks(chunks);
            log.info("Vector ingestion completed for {} chunks", chunks.size());

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Vector embedding and storage failed for documentId={}", documentId, e);
            throw new BusinessException("Vector embedding failed for documentId " + documentId, e);
        }
    }

    /**
     * TX3: No transaction - Elasticsearch indexing is eventually-consistent.
     * Failures are logged but do not fail the document (pgvector is primary search index).
     */
    private void indexInElasticsearch(Long documentId, Long groupId) {
        log.info("TX3 (NO TX): Starting Elasticsearch indexing for documentId={}", documentId);

        try {
            List<DocumentChunkEntity> chunks = documentChunkMapper.selectByDocumentIdAndGroupId(documentId, groupId);

            if (chunks.isEmpty()) {
                log.warn("No chunks found for Elasticsearch indexing: documentId={}", documentId);
                return;
            }

            String fileName = getDocumentFileName(documentId);
            elasticsearchChunkIndexService.indexChunks(fileName, chunks);
            log.info("Elasticsearch indexing completed for {} chunks", chunks.size());

        } catch (Exception e) {
            log.error("Elasticsearch indexing failed for documentId={} (non-blocking, pgvector is primary)",
                documentId, e);
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
}