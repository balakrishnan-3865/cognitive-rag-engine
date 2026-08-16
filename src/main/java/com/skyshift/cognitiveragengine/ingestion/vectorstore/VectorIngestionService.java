package com.skyshift.cognitiveragengine.ingestion.vectorstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.ingestion.exception.NoChunksFoundException;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VectorIngestionService {

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final EmbeddingBatchExecutor embeddingBatchExecutor;

    public VectorIngestionService(
            VectorStore vectorStore,
            ObjectMapper objectMapper,
            @Value("${spring.ai.vectorstore.pgvector.max-document-batch-size:100}") int batchSize,
            EmbeddingBatchExecutor embeddingBatchExecutor) {
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.embeddingBatchExecutor = embeddingBatchExecutor;
    }

    public void embedAndStoreDocumentChunks(Long documentId, List<DocumentChunkEntity> chunks) {

        if(chunks == null || chunks.isEmpty()) {
            throw new NoChunksFoundException("No document chunks found for documentId " + documentId);
        }

        try {
            deleteExistingEmbeddings(documentId);

            List<Document> documents = convertToSpringAIDocuments(chunks);
            List<List<Document>> batches = partitionIntoBatches(documents, batchSize);
            int totalBatches = batches.size();

            log.info("Starting pgVector embedding: documentId={}, totalChunks={}, totalBatches={}",
                    documentId, chunks.size(), totalBatches);

            for (int batchNum = 0; batchNum < totalBatches; batchNum++) {
                List<Document> batch = batches.get(batchNum);
                embeddingBatchExecutor.embedBatchWithRetry(batch, batchNum, totalBatches, documentId);
            }

            log.info("pgVector embedding completed successfully: documentId={}, embeddedChunks={}",
                     documentId, chunks.size());
        } catch (Exception e) {
            log.error("pgVector embedding failed: documentId={}, failureMessage={}. Initiating compensating rollback...",
                     documentId, e.getMessage());
            compensatingRollback(documentId);

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
        try {
            deleteExistingEmbeddings(documentId);
            log.info("pgVector rollback completed: documentId={}", documentId);

        } catch (Exception exception) {
            log.error("CRITICAL: pgVector rollback FAILED: documentId={}. " +
                     "Manual cleanup required. Orphaned vectors may exist for this documentId.",
                     documentId, exception);

            throw new RuntimeException(
                "Compensating rollback failed for documentId " + documentId +
                ". Manual vector cleanup required. Error: " + exception.getMessage(),
                exception);
        }
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

        } catch (Exception e) {
            log.error("pgVector deletion failed: documentId={}, error={}", documentId, e.getMessage(), e);
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

                // Null-valued fields are dropped rather than passed through — Spring AI's
                // Document rejects any null metadata value outright, and Docling-produced
                // chunks legitimately have null fields sometimes (e.g. sectionPath before the
                // first header in a document).
                jsonMetadata.forEach((key, value) -> {
                    if (value != null && !metadata.containsKey(key)) {
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