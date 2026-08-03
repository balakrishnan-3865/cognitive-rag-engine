package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.ingestion.exception.NoChunksFoundException;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.vectorstore.VectorIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class VectorEmbeddingTransactionExecutor {

    private final VectorIngestionService vectorIngestionService;

    public VectorEmbeddingTransactionExecutor(VectorIngestionService vectorIngestionService) {
        this.vectorIngestionService = vectorIngestionService;
    }

    /**
     * Extracted into its own bean so {@code @Transactional} is applied via the Spring AOP proxy.
     * Called via self-invocation from within the same class, the annotation would be silently skipped.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void embedAndStoreVectors(Long documentId, List<DocumentChunkEntity> chunks) {
        try {
            vectorIngestionService.embedAndStoreDocumentChunks(documentId, chunks);

        } catch (NoChunksFoundException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("pgVector stage failed: documentId={}, error={}", documentId, exception.getMessage());
            throw new BusinessException("Vector embedding failed: " + exception.getMessage(), exception);
        }
    }
}