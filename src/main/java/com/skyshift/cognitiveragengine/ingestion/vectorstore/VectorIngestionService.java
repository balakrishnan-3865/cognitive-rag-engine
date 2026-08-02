package com.skyshift.cognitiveragengine.ingestion.vectorstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.ingestion.exception.NoChunksFoundException;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VectorIngestionService {

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public VectorIngestionService(
            VectorStore vectorStore,
            ObjectMapper objectMapper,
            @Value("${spring.ai.vectorstore.pgvector.max-document-batch-size:100}") int batchSize) {
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    public void embedAndStoreDocumentChunks(Long documentId, List<DocumentChunkEntity> chunks) {

        if(chunks == null || chunks.isEmpty()) {
            log.warn("No document chunks found for documentId={}", documentId);
            throw new NoChunksFoundException("No document chunks found for documentId " + documentId);
        }

        try {
            // Delete any existing embeddings (idempotent re-ingestion safety)
            deleteExistingEmbeddings(documentId);

            // Partition chunks into batches
            List<Document> documents = convertToSpringAIDocuments(chunks);
            List<List<Document>> batches = partitionIntoBatches(documents, batchSize);

            log.info("Embedding {} chunks in {} batches for documentId={}",
                    chunks.size(), batches.size(), documentId);

            // Process batches sequentially with per-batch retry and transaction isolation
            int totalBatches = batches.size();
            for (int batchNum = 0; batchNum < totalBatches; batchNum++) {
                List<Document> batch = batches.get(batchNum);
                embedBatchWithRetry(batch, batchNum, totalBatches, documentId);
            }

            log.info("Embedding completed successfully for documentId={}", documentId);
        } catch (Exception e) {
            log.error("Embedding failed for documentId={}", documentId, e);
            compensatingRollback(documentId);  // Trigger compensating rollback

            throw new BusinessException(
                    "Embedding failed for documentId " + documentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Compensating rollback: Called when embedding fails (after per-batch retries exhausted).
     * Deletes all vectors for this document from pgVector to maintain consistency.
     * Result: Document marked FAILED at orchestrator level with failure reason.
     */
    private void compensatingRollback(Long documentId) {
        log.error("Triggering compensating rollback for documentId={}: " +
                 "deleting all vectors to maintain consistency", documentId);

        try {
            deleteExistingEmbeddings(documentId);
            log.info("Compensating rollback COMPLETE: deleted all vectors for documentId={}", documentId);

        } catch (Exception exception) {
            log.error("CRITICAL: Compensating rollback FAILED for documentId={}. " +
                     "Manual cleanup of vectors may be required.", documentId, exception);

            throw new RuntimeException(
                "Compensating rollback failed for documentId " + documentId +
                ". Manual vector cleanup required. Error: " + exception.getMessage(),
                exception);
        }
    }

    /**
     * Embeds a single batch with per-batch retry and exponential backoff.
     * Each batch gets its own transaction (REQUIRES_NEW) for isolation.
     * If batch fails after max retries, fallback throws BusinessException.
     * <p>
     * Retry: 3 max attempts with exponential backoff (100ms initial, 2x multiplier)
     * Configuration: application.yaml resilience4j.retry.instances.embedding-batch
     */
    @Retry(name = "embedding-batch", fallbackMethod = "handleBatchEmbeddingFailure")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void embedBatchWithRetry(List<Document> batch, int batchNum, int totalBatches, Long documentId) {
        try {
            vectorStore.add(batch);
            log.debug("Embedded batch {} of {} ({} documents) for documentId={}",
                     batchNum + 1, totalBatches, batch.size(), documentId);

        } catch (Exception e) {
            log.warn("Embedding failed for batch {} of {} (documentId={}, will retry)",
                    batchNum + 1, totalBatches, documentId, e);
            throw new RuntimeException(
                "Batch " + batchNum + " embedding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fallback: Called when @Retry exhausts max attempts for a batch.
     * Throws BusinessException to trigger compensating rollback at orchestrator level.
     * <p>
     * Method signature must match embedBatchWithRetry() plus Throwable parameter.
     */
    private void handleBatchEmbeddingFailure(
            List<Document> batch, int batchNum, int totalBatches, Long documentId, Throwable t) {
        log.error("Batch {} embedding PERMANENTLY FAILED after max retry attempts for documentId={}. " +
                 "Triggering compensating rollback at orchestrator level.",
                 batchNum + 1, documentId, t);

        throw new BusinessException(
            "Batch " + (batchNum + 1) + " embedding failed after max retry attempts: " + t.getMessage(), t);
    }

    /**
     * Deletes all existing embeddings for a document from pgVector.
     * Used before ingestion (idempotent re-ingestion), after max retry failure (compensating rollback),
     * and when Elasticsearch indexing fails (cross-service compensating rollback).
     */
    public void deleteExistingEmbeddings(Long documentId) {
        try {
            Filter.Expression filter = new FilterExpressionBuilder()
                .eq("documentId", documentId)
                .build();

            vectorStore.delete(filter);
            log.debug("Deleted existing embeddings for documentId={}", documentId);

        } catch (Exception e) {
            log.error("Failed to delete existing embeddings for documentId={}", documentId, e);
            throw new RuntimeException(
                "Cannot delete existing embeddings for documentId " + documentId +
                ". Vector store may be in inconsistent state.", e);
        }
    }

    /**
     * Convert DocumentChunkEntity list to Spring AI Document list.
     */
    private List<Document> convertToSpringAIDocuments(List<DocumentChunkEntity> chunks) {
        return chunks.stream()
                .map(this::convertToSpringAIDocument)
                .collect(Collectors.toList());
    }

    /**
     * Convert single DocumentChunkEntity to Spring AI Document.
     */
    private Document convertToSpringAIDocument(DocumentChunkEntity chunk) {
        String documentId = buildStableDocumentId(chunk);
        Map<String, Object> metadata = buildMetadata(chunk);

        return Document.builder()
                .id(documentId)
                .text(chunk.getChunkText())
                .metadata(metadata)
                .build();
    }

    /**
     * Build stable, deterministic ID for idempotent re-ingestion.
     */
    private String buildStableDocumentId(DocumentChunkEntity chunk) {
        String rawId = chunk.getDocumentId() + ":" + chunk.getChunkNumber();
        return UUID.nameUUIDFromBytes(rawId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Build complete metadata map for document chunk.
     */
    private Map<String, Object> buildMetadata(DocumentChunkEntity chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        // Column-based fields
        metadata.put("chunkId", chunk.getId());
        metadata.put("chunkNumber", chunk.getChunkNumber());
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("groupId", chunk.getGroupId());

        // Merge JSON metadata if present
        if (chunk.getMetadataJson() != null && !chunk.getMetadataJson().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> jsonMetadata = objectMapper.readValue(
                        chunk.getMetadataJson(),
                        Map.class);

                jsonMetadata.forEach((key, value) -> {
                    if (!metadata.containsKey(key)) {
                        metadata.put(key, value);
                    }
                });
            } catch (Exception ex) {
                log.warn("Failed to deserialize metadata JSON for chunk {}: {}",
                        chunk.getId(), ex.getMessage());
            }
        }

        return metadata;
    }

    /**
     * Partition list into batches of specified size.
     */
    private <T> List<List<T>> partitionIntoBatches(List<T> list, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            int end = Math.min(i + batchSize, list.size());
            batches.add(list.subList(i, end));
        }
        return batches;
    }
}