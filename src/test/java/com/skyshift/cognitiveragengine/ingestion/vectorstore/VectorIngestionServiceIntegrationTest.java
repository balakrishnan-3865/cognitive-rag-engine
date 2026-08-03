package com.skyshift.cognitiveragengine.ingestion.vectorstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.*;

import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties = "spring.ai.vectorstore.pgvector.max-document-batch-size=10")
@DisplayName("VectorIngestionService Integration Tests")
class VectorIngestionServiceIntegrationTest {

    @MockBean
    private VectorStore vectorStore;

    private VectorIngestionService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new VectorIngestionService(vectorStore, objectMapper, 10, new EmbeddingBatchExecutor(vectorStore));
    }

    @Test
    @DisplayName("Should delete existing embeddings before ingesting multiple chunks")
    void testDeleteBeforeIngestion() {
        List<DocumentChunkEntity> chunks = createChunkList(25);

        service.embedAndStoreDocumentChunks(100L, chunks);

        verify(vectorStore, times(1)).delete(any(Filter.Expression.class));
        ArgumentCaptor<Filter.Expression> deleteCaptor = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(vectorStore).delete(deleteCaptor.capture());

        Filter.Expression filter = deleteCaptor.getValue();
        assertNotNull(filter, "Delete filter expression should be created");
    }

    @Test
    @DisplayName("Should ingest multiple chunks with batch size from configuration")
    void testIngestWithConfiguredBatchSize() {
        List<DocumentChunkEntity> chunks = createChunkList(25);

        service.embedAndStoreDocumentChunks(100L, chunks);

        verify(vectorStore).delete(any(Filter.Expression.class));
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(3)).add(captor.capture());

        List<List<Document>> batches = captor.getAllValues();
        assertEquals(10, batches.get(0).size());
        assertEquals(10, batches.get(1).size());
        assertEquals(5, batches.get(2).size());
    }

    @Test
    @DisplayName("Should ingest large document set with proper batching")
    void testIngestLargeDocumentSet() {
        List<DocumentChunkEntity> chunks = createChunkList(105);

        service.embedAndStoreDocumentChunks(100L, chunks);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(11)).add(captor.capture());

        List<List<Document>> batches = captor.getAllValues();
        for (int i = 0; i < 10; i++) {
            assertEquals(10, batches.get(i).size());
        }
        assertEquals(5, batches.get(10).size());
    }

    @Test
    @DisplayName("Should maintain document integrity through complete ingestion pipeline")
    void testDocumentIntegrityThroughPipeline() {
        DocumentChunkEntity chunk = createComplexChunk();

        service.embedAndStoreDocumentChunks(100L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Document doc = captor.getValue().get(0);
        assertEquals("This is a complex test chunk", doc.getText());

        Map<String, Object> metadata = doc.getMetadata();
        assertEquals(1, metadata.get("chunkNumber"));
        assertEquals(100L, metadata.get("documentId"));
        assertEquals(50L, metadata.get("groupId"));
        assertEquals("pdf", metadata.get("source"));
        assertEquals(1, metadata.get("pageNumber"));
    }

    @Test
    @DisplayName("Should handle mixed metadata: column and JSON fields")
    void testMixedMetadataHandling() {
        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");
        chunk.setChunkNumber(10);
        chunk.setGroupId(20L);

        Map<String, Object> jsonMeta = new LinkedHashMap<>();
        jsonMeta.put("language", "en");
        jsonMeta.put("sentiment", "positive");
        jsonMeta.put("chunkNumber", 999);

        chunk.setMetadataJson(convertToJson(jsonMeta));

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Map<String, Object> metadata = captor.getValue().get(0).getMetadata();
        assertEquals(10, metadata.get("chunkNumber"), "Column field takes precedence");
        assertEquals(20L, metadata.get("groupId"));
        assertEquals("en", metadata.get("language"));
        assertEquals("positive", metadata.get("sentiment"));
    }

    @Test
    @DisplayName("Should include all required metadata fields for vector search filtering")
    void testCompleteMetadataForVectorSearch() {
        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");
        chunk.setChunkNumber(10);
        chunk.setGroupId(20L);

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Document doc = captor.getValue().get(0);
        assertNotNull(doc);
        assertEquals("Text", doc.getText());

        Map<String, Object> metadata = doc.getMetadata();
        assertNotNull(metadata.get("chunkNumber"), "chunkNumber required for filtering");
        assertNotNull(metadata.get("documentId"), "documentId required for filtering");
        assertNotNull(metadata.get("groupId"), "groupId required for filtering");
    }

    @Test
    @DisplayName("Should process different document types in single batch")
    void testMixedDocumentTypes() {
        List<DocumentChunkEntity> chunks = new ArrayList<>();

        DocumentChunkEntity pdfChunk = createChunkWithMetadata(1L, 1L, "PDF content", "{\"type\":\"pdf\"}");
        DocumentChunkEntity txtChunk = createChunkWithMetadata(2L, 2L, "Text content", "{\"type\":\"txt\"}");
        DocumentChunkEntity docChunk = createChunkWithMetadata(3L, 3L, "Doc content", "{\"type\":\"doc\"}");

        chunks.addAll(List.of(pdfChunk, txtChunk, docChunk));

        service.embedAndStoreDocumentChunks(1L, chunks);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        List<Document> documents = captor.getValue();
        assertEquals(3, documents.size());
        assertEquals("pdf", documents.get(0).getMetadata().get("type"));
        assertEquals("txt", documents.get(1).getMetadata().get("type"));
        assertEquals("doc", documents.get(2).getMetadata().get("type"));
    }

    @Test
    @DisplayName("Should generate unique IDs for different chunks")
    void testUniqueIdGeneration() {
        List<DocumentChunkEntity> chunks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            chunks.add(createSampleChunk((long) i, (long) i, "Text " + i));
        }

        service.embedAndStoreDocumentChunks(1L, chunks);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(1)).add(captor.capture());

        List<Document> documents = captor.getValue();
        Set<String> ids = new HashSet<>();
        for (Document doc : documents) {
            assertTrue(ids.add(doc.getId()), "Document IDs should be unique");
        }
    }

    @Test
    @DisplayName("Should handle empty metadata JSON")
    void testEmptyMetadataJson() {
        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");
        chunk.setMetadataJson("");

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Document doc = captor.getValue().get(0);
        assertNotNull(doc.getMetadata());
        assertTrue(doc.getMetadata().containsKey("chunkNumber"));
    }

    @Test
    @DisplayName("Should handle null metadata JSON")
    void testNullMetadataJson() {
        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");
        chunk.setMetadataJson(null);

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Document doc = captor.getValue().get(0);
        assertNotNull(doc.getMetadata());
        assertEquals(1L, doc.getMetadata().get("documentId"));
    }

    @Test
    @DisplayName("Should fail ingestion on vectorStore add error after successful delete")
    void testIngestionFailureHandling() {
        doThrow(new RuntimeException("VectorStore connection failed"))
                .when(vectorStore).add(anyList());

        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");

        assertThrows(Exception.class, () ->
                service.embedAndStoreDocumentChunks(1L, List.of(chunk)));

        verify(vectorStore, times(2)).delete(any(Filter.Expression.class));
    }

    @Test
    @DisplayName("Should prevent insert if delete fails, maintaining consistent state")
    void testDeleteFailurePreventInsert() {
        doThrow(new RuntimeException("VectorStore delete failed"))
                .when(vectorStore).delete(any(Filter.Expression.class));

        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");

        assertThrows(RuntimeException.class, () ->
                service.embedAndStoreDocumentChunks(1L, List.of(chunk)));

        verify(vectorStore, times(2)).delete(any(Filter.Expression.class));
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("Should stop processing on first batch failure after successful delete")
    void testFailureStopsProcessing() {
        VectorIngestionService smallBatchService =
                new VectorIngestionService(vectorStore, objectMapper, 2, new EmbeddingBatchExecutor(vectorStore));

        doThrow(new RuntimeException("VectorStore add error"))
                .when(vectorStore).add(anyList());

        List<DocumentChunkEntity> chunks = createChunkList(5);

        assertThrows(Exception.class, () ->
                smallBatchService.embedAndStoreDocumentChunks(100L, chunks));

        verify(vectorStore, times(2)).delete(any(Filter.Expression.class));
        verify(vectorStore, times(1)).add(anyList());
    }

    // Helper methods

    private DocumentChunkEntity createSampleChunk(Long documentId, Long chunkNumber, String text) {
        return DocumentChunkEntity.builder()
                .id(documentId)
                .documentId(documentId)
                .chunkNumber(chunkNumber.intValue())
                .chunkText(text)
                .groupId(1L)
                .startPosition(0)
                .endPosition(text.length())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private DocumentChunkEntity createComplexChunk() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "pdf");
        metadata.put("pageNumber", 1);

        return DocumentChunkEntity.builder()
                .id(100L)
                .documentId(100L)
                .chunkNumber(1)
                .chunkText("This is a complex test chunk")
                .groupId(50L)
                .metadataJson(convertToJson(metadata))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private DocumentChunkEntity createChunkWithMetadata(
            Long documentId, Long chunkNumber, String text, String metadata) {
        return DocumentChunkEntity.builder()
                .id(documentId)
                .documentId(documentId)
                .chunkNumber(chunkNumber.intValue())
                .chunkText(text)
                .groupId(1L)
                .startPosition(0)
                .endPosition(text.length())
                .metadataJson(metadata)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private List<DocumentChunkEntity> createChunkList(int count) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            chunks.add(createSampleChunk((long) i, (long) i, "Chunk text " + i));
        }
        return chunks;
    }

    private String convertToJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert metadata to JSON", e);
        }
    }
}