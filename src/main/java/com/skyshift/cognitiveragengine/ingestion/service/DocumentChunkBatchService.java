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

    // Keeps each insert well under Postgres's bind-parameter limit per statement.
    private static final int POSTGRES_PARAMETER_LIMIT = 32_767;
    private static final int INSERT_BATCH_PARAMETER_COUNT = 9;
    private static final int MAX_INSERT_BATCH_SIZE = POSTGRES_PARAMETER_LIMIT / INSERT_BATCH_PARAMETER_COUNT;

    private final DocumentChunkMapper documentChunkMapper;

    public DocumentChunkBatchService(DocumentChunkMapper documentChunkMapper) {
        this.documentChunkMapper = documentChunkMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public int batchInsertWithIdempotency(
            Long documentId, List<DocumentChunkEntity> newChunks) {

        log.info("Batch insert with idempotency: documentId={}, chunkCount={}",
            documentId, newChunks.size());

        try {
            int deletedCount = documentChunkMapper.deleteByDocumentId(documentId);
            log.info("Deleted {} existing chunks for idempotency", deletedCount);

            insertInBatches(newChunks);
            log.info("Batch inserted {} new chunks", newChunks.size());

            return newChunks.size();

        } catch (Exception e) {
            log.error("Batch insert failed for documentId={}", documentId, e);
            throw e;
        }
    }

    private void insertInBatches(List<DocumentChunkEntity> chunks) {
        int total = chunks.size();

        if(total <= MAX_INSERT_BATCH_SIZE) {
            documentChunkMapper.batchInsertChunks(chunks);
            return;
        }

        for (int start = 0; start < total; start += MAX_INSERT_BATCH_SIZE) {
            int end = Math.min(start + MAX_INSERT_BATCH_SIZE, total);
            documentChunkMapper.batchInsertChunks(chunks.subList(start, end));
        }
    }
}