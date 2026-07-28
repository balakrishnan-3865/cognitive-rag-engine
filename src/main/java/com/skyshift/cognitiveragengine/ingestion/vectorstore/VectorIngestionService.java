package com.skyshift.cognitiveragengine.ingestion.vectorstore;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;

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

    /**
     * Ingests a list of DocumentChunkEntity objects into the vector store.
     * Deletes existing embeddings for the document first to ensure idempotency,
     * then converts chunks to Spring AI Document format and processes in configurable batches.
     */
    public void ingestDocumentChunks(List<DocumentChunkEntity> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("No document chunks to ingest");
            return;
        }

        log.info("Starting vector ingestion for {} document chunks with batch size: {}", chunks.size(), batchSize);

        Long documentId = extractDocumentId(chunks);
        deleteExistingEmbeddings(documentId);

        List<Document> documents = convertToSpringAIDocuments(chunks);
        processBatches(documents);

        log.info("Vector ingestion completed. Total documents ingested: {} for documentId: {}",
                chunks.size(), documentId);
    }

    /**
     * Extracts the documentId from the first chunk.
     * All chunks in a batch should belong to the same document.
     */
    private Long extractDocumentId(List<DocumentChunkEntity> chunks) {
        return chunks.get(0).getDocumentId();
    }

    /**
     * Deletes all existing embeddings for a given documentId from the vector store.
     * This ensures idempotent re-ingestion behavior and maintains consistency.
     * Must complete successfully before new embeddings are inserted.
     *
     * @param documentId the document ID whose embeddings should be deleted
     * @throws RuntimeException if deletion fails, preventing inconsistent state
     */
    private void deleteExistingEmbeddings(Long documentId) {
        try {
            Filter.Expression filter = new FilterExpressionBuilder().eq("documentId", documentId).build();
            vectorStore.delete(filter);
            log.info("Successfully deleted existing embeddings for documentId: {}", documentId);
        } catch (Exception e) {
            log.error("Failed to delete existing embeddings for documentId {}: {}", documentId, e.getMessage(), e);
            throw new RuntimeException(
                    "Cannot proceed with vector ingestion: failed to delete existing embeddings for documentId " +
                            documentId + ". Vector store may be in inconsistent state.", e);
        }
    }

    /**
     * Converts a list of DocumentChunkEntity objects to Spring AI Document objects.
     */
    private List<Document> convertToSpringAIDocuments(List<DocumentChunkEntity> chunks) {
        return chunks.stream()
                .map(this::convertToSpringAIDocument)
                .collect(Collectors.toList());
    }

    /**
     * Converts a single DocumentChunkEntity to a Spring AI Document.
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
     * Builds a stable, deterministic UUID-based ID for the document.
     * Same input always produces the same ID, enabling idempotent re-ingestion.
     */
    private String buildStableDocumentId(DocumentChunkEntity chunk) {
        String rawId = chunk.getDocumentId() + ":" + chunk.getChunkNumber();
        return UUID.nameUUIDFromBytes(rawId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Builds complete metadata map for a document chunk.
     * Merges column-based fields with JSON metadata, with column fields taking precedence.
     */
    private Map<String, Object> buildMetadata(DocumentChunkEntity chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        // Set column-based fields first (these take precedence)
        metadata.put("chunkNumber", chunk.getChunkNumber());
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("groupId", chunk.getGroupId());
        metadata.put("startPosition", chunk.getStartPosition());
        metadata.put("endPosition", chunk.getEndPosition());

        // Merge JSON metadata if present, avoiding overwrites
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
            } catch (Exception e) {
                log.warn("Failed to deserialize metadata JSON for chunk {}: {}", chunk.getId(), e.getMessage());
            }
        }

        return metadata;
    }

    /**
     * Processes documents in configurable batches and stores them in the vector store.
     */
    private void processBatches(List<Document> documents) {
        List<List<Document>> batches = partitionIntoBatches(documents, batchSize);

        for (int i = 0; i < batches.size(); i++) {
            List<Document> batch = batches.get(i);
            try {
                vectorStore.add(batch);
                log.debug("Processed batch {} of {} ({} documents)",
                        i + 1, batches.size(), batch.size());
            } catch (Exception e) {
                log.error("Failed to ingest batch {} of {}: {}",
                        i + 1, batches.size(), e.getMessage(), e);
                throw new RuntimeException("Vector ingestion failed at batch " + (i + 1), e);
            }
        }
    }

    /**
     * Partitions a list into batches of specified size.
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