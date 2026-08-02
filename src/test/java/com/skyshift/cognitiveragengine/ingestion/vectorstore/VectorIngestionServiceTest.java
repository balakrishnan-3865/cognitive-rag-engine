package com.skyshift.cognitiveragengine.ingestion.vectorstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.ingestion.exception.NoChunksFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.time.LocalDateTime;
import java.util.*;

import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("VectorIngestionService Unit Tests")
class VectorIngestionServiceTest {

    @Mock
    private VectorStore vectorStore;

    private VectorIngestionService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        service = new VectorIngestionService(vectorStore, objectMapper, 100);
    }

    @Test
    @DisplayName("Should throw exception on empty list")
    void testIngestEmptyList() {
        assertThrows(NoChunksFoundException.class, () ->
                service.embedAndStoreDocumentChunks(1L, Collections.emptyList()));
        verify(vectorStore, never()).add(any());
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
    }

    @Test
    @DisplayName("Should throw exception on null input")
    void testIngestNullList() {
        assertThrows(NoChunksFoundException.class, () ->
                service.embedAndStoreDocumentChunks(1L, null));
        verify(vectorStore, never()).add(any());
    }

    @Test
    @DisplayName("Should delete existing embeddings before ingesting")
    void testDeleteBeforeIngest() {
        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Sample chunk text");

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<Filter.Expression> deleteCaptor = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(vectorStore).delete(deleteCaptor.capture());

        Filter.Expression deleteFilter = deleteCaptor.getValue();
        assertNotNull(deleteFilter, "Delete filter should be provided");
    }

    @Test
    @DisplayName("Should convert DocumentChunkEntity to Spring AI Document correctly")
    void testConvertDocumentChunkToSpringAIDocument() {
        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Sample chunk text");
        chunk.setMetadataJson("{\"source\":\"pdf\",\"pageNumber\":1}");

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        verify(vectorStore).delete(any(Filter.Expression.class));
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        List<Document> documents = captor.getValue();
        assertEquals(1, documents.size());

        Document doc = documents.get(0);
        assertEquals("Sample chunk text", doc.getText());
        assertNotNull(doc.getId());
        assertTrue(doc.getId().length() > 0);
    }

    @Test
    @DisplayName("Should generate stable IDs for same chunks")
    void testStableIdGeneration() {
        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor1 = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor1.capture());
        String id1 = captor1.getValue().get(0).getId();

        reset(vectorStore);

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor2 = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor2.capture());
        String id2 = captor2.getValue().get(0).getId();

        assertEquals(id1, id2, "Same input should generate same ID");
    }

    @Test
    @DisplayName("Should ensure idempotent ingestion by deleting old embeddings before adding new ones")
    void testIdempotentIngestion() {
        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<Filter.Expression> deleteCaptor = ArgumentCaptor.forClass(Filter.Expression.class);
        ArgumentCaptor<List<Document>> addCaptor = ArgumentCaptor.forClass(List.class);

        verify(vectorStore).delete(deleteCaptor.capture());
        verify(vectorStore).add(addCaptor.capture());

        Filter.Expression deleteFilter = deleteCaptor.getValue();
        assertNotNull(deleteFilter, "Delete filter should target correct documentId");
        assertEquals(1, addCaptor.getValue().size());
    }

    @Test
    @DisplayName("Should build metadata with column fields taking precedence")
    void testMetadataBuilding() throws JsonProcessingException {
        Map<String, Object> jsonMetadata = new LinkedHashMap<>();
        jsonMetadata.put("source", "pdf");
        jsonMetadata.put("chunkNumber", 999); // This should NOT override column value
        jsonMetadata.put("pageNumber", 1);

        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");
        chunk.setChunkNumber(5);
        chunk.setMetadataJson(objectMapper.writeValueAsString(jsonMetadata));

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Map<String, Object> metadata = captor.getValue().get(0).getMetadata();
        assertEquals(5, metadata.get("chunkNumber"), "Column field should take precedence");
        assertEquals("pdf", metadata.get("source"));
        assertEquals(1, metadata.get("pageNumber"));
    }

    @Test
    @DisplayName("Should include all column-based metadata fields")
    void testAllColumnFieldsInMetadata() {
        DocumentChunkEntity chunk = createSampleChunk(1L, 2L, "Text");
        chunk.setChunkNumber(3);
        chunk.setGroupId(4L);
        chunk.setStartPosition(10);
        chunk.setEndPosition(50);

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Map<String, Object> metadata = captor.getValue().get(0).getMetadata();
        assertEquals(3, metadata.get("chunkNumber"));
        assertEquals(1L, metadata.get("documentId"));
        assertEquals(4L, metadata.get("groupId"));
        assertEquals(10, metadata.get("startPosition"));
        assertEquals(50, metadata.get("endPosition"));
    }

    @Test
    @DisplayName("Should handle malformed JSON metadata gracefully")
    void testHandleMalformedJsonMetadata() {
        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");
        chunk.setMetadataJson("{invalid json}");

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Document doc = captor.getValue().get(0);
        assertNotNull(doc);
        assertEquals("Text", doc.getText());
    }

    @Test
    @DisplayName("Should batch documents correctly")
    void testBatchingWithSmallBatchSize() {
        VectorIngestionService smallBatchService = new VectorIngestionService(vectorStore, objectMapper, 2);

        List<DocumentChunkEntity> chunks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            chunks.add(createSampleChunk((long) i, (long) i, "Text " + i));
        }

        smallBatchService.embedAndStoreDocumentChunks(100L, chunks);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(3)).add(captor.capture());

        List<List<Document>> allBatches = captor.getAllValues();
        assertEquals(2, allBatches.get(0).size());
        assertEquals(2, allBatches.get(1).size());
        assertEquals(1, allBatches.get(2).size());
    }

    @Test
    @DisplayName("Should fail if delete operation fails before attempting insert")
    void testDeleteFailurePreventsInsert() {
        doThrow(new RuntimeException("VectorStore delete failed"))
                .when(vectorStore).delete(any(Filter.Expression.class));

        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");

        assertThrows(RuntimeException.class, () ->
                service.embedAndStoreDocumentChunks(1L, List.of(chunk)));

        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    @DisplayName("Should fail and rethrow exception from vectorStore add")
    void testAddExceptionHandling() {
        doThrow(new RuntimeException("VectorStore add failure"))
                .when(vectorStore).add(anyList());

        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");

        assertThrows(RuntimeException.class, () ->
                service.embedAndStoreDocumentChunks(1L, List.of(chunk)));

        verify(vectorStore).delete(any(Filter.Expression.class));
    }

    @Test
    @DisplayName("Should preserve content integrity during conversion")
    void testContentPreservation() {
        String testContent = "This is a test chunk with special chars: !@#$%^&*()_+-=[]{}|;:',.<>?/";
        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, testContent);

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        assertEquals(testContent, captor.getValue().get(0).getText());
    }

    @Test
    @DisplayName("Should handle metadata with nested structures")
    void testComplexMetadataHandling() throws Exception {
        Map<String, Object> jsonMetadata = new LinkedHashMap<>();
        jsonMetadata.put("source", "pdf");
        jsonMetadata.put("tags", List.of("important", "review"));
        jsonMetadata.put("confidence", 0.95);

        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");
        chunk.setMetadataJson(objectMapper.writeValueAsString(jsonMetadata));

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Map<String, Object> metadata = captor.getValue().get(0).getMetadata();
        assertEquals("pdf", metadata.get("source"));
        assertEquals(List.of("important", "review"), metadata.get("tags"));
        assertEquals(0.95, metadata.get("confidence"));
    }

    @Test
    @DisplayName("Should skip duplicate metadata keys when merging")
    void testMetadataMergeWithDuplicates() throws Exception {
        Map<String, Object> jsonMetadata = new LinkedHashMap<>();
        jsonMetadata.put("chunkNumber", 999);
        jsonMetadata.put("documentId", 999L);
        jsonMetadata.put("customField", "customValue");

        DocumentChunkEntity chunk = createSampleChunk(1L, 1L, "Text");
        chunk.setChunkNumber(5);
        chunk.setMetadataJson(objectMapper.writeValueAsString(jsonMetadata));

        service.embedAndStoreDocumentChunks(1L, List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Map<String, Object> metadata = captor.getValue().get(0).getMetadata();
        assertEquals(5, metadata.get("chunkNumber"), "Column field should be preserved");
        assertEquals(1L, metadata.get("documentId"), "Column field should be preserved");
        assertEquals("customValue", metadata.get("customField"), "New field should be added");
    }

    // Helper method to create sample chunks
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
}