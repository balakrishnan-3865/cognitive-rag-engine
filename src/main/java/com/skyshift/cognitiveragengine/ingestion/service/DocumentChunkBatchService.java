package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class DocumentChunkBatchService {

    private final DocumentChunkMapper documentChunkMapper;

    public DocumentChunkBatchService(DocumentChunkMapper documentChunkMapper) {
        this.documentChunkMapper = documentChunkMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public int batchInsertWithIdempotency(
            Long documentId, Long groupId, List<DocumentChunkEntity> newChunks) {

        log.info("Batch insert with idempotency: documentId={}, chunkCount={}",
            documentId, newChunks.size());

        try {
            int deletedCount = documentChunkMapper.deleteByDocumentIdAndGroupId(documentId, groupId);
            log.info("Deleted {} existing chunks for idempotency", deletedCount);

            documentChunkMapper.batchInsertChunks(newChunks);
            log.info("Batch inserted {} new chunks", newChunks.size());

            return newChunks.size();

        } catch (Exception e) {
            log.error("Batch insert failed for documentId={}", documentId, e);
            throw e;
        }
    }
}