package com.skyshift.cognitiveragengine.retrieval.vectorstore;

import com.skyshift.cognitiveragengine.retrieval.vectorstore.exception.VectorSearchException;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.model.VectorHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class VectorSearchServiceIntegrationTest {

    @MockBean
    private VectorStore vectorStore;

    private VectorSearchService service;

    @BeforeEach
    void setUp() {
        service = new VectorSearchService(vectorStore);
    }

    @Test
    void testSearchIntegration_WithValidDocuments() {
        Map<String, Object> metadata = createMetadata(1L, 100L, 1, 0, 100);
        Document doc = Document.builder()
                .id("stable-uuid-1")
                .text("Test chunk content")
                .score(0.92)
                .metadata(metadata)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        List<VectorHit> results = service.search("test query", 100L, 10);

        assertEquals(1, results.size());
        VectorHit hit = results.get(0);
        assertEquals("stable-uuid-1", hit.id());
        assertEquals("Test chunk content", hit.content());
        assertEquals(1L, hit.documentId());
        assertEquals(100L, hit.groupId());
        assertEquals(1, hit.chunkNumber());
        assertEquals(0.92, hit.score());
    }

    @Test
    void testSearchIntegration_MultiTenantIsolation() {
        Map<String, Object> metadata1 = createMetadata(1L, 100L, 1, 0, 100);
        Map<String, Object> metadata2 = createMetadata(2L, 200L, 1, 0, 100);

        Document doc1 = Document.builder().id("doc-1").text("Tenant 100").score(0.9).metadata(metadata1).build();
        Document doc2 = Document.builder().id("doc-2").text("Tenant 200").score(0.9).metadata(metadata2).build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc1));

        List<VectorHit> results = service.search("query", 100L, 10);

        assertEquals(1, results.size());
        assertEquals(100L, results.get(0).groupId());
        assertEquals("Tenant 100", results.get(0).content());
    }

    @Test
    void testSearchIntegration_BatchRetrieval() {
        List<Document> batchResults = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            Map<String, Object> metadata = createMetadata(1L, 100L, i, (i - 1) * 100, i * 100);
            batchResults.add(Document.builder()
                    .id("doc-" + i)
                    .text("Chunk " + i)
                    .score(0.95 - (i * 0.01))
                    .metadata(metadata)
                    .build());
        }

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(batchResults);

        List<VectorHit> results = service.search("query", 100L, 20);

        assertEquals(15, results.size());
        for (int i = 0; i < 15; i++) {
            VectorHit hit = results.get(i);
            assertEquals(1L, hit.documentId());
            assertEquals(100L, hit.groupId());
            assertEquals(i + 1, hit.chunkNumber());
        }
    }

    @Test
    void testSearchIntegration_RespectTopK() {
        List<Document> manyResults = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            Map<String, Object> metadata = createMetadata(1L, 100L, i, 0, 100);
            manyResults.add(Document.builder()
                    .id("doc-" + i)
                    .text("Content " + i)
                    .score(0.90)
                    .metadata(metadata)
                    .build());
        }

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(manyResults.stream().limit(10).toList());

        List<VectorHit> results = service.search("query", 100L, 10);

        assertEquals(10, results.size());
    }

    @Test
    void testSearchIntegration_AllRequiredFieldsPopulated() {
        Map<String, Object> metadata = createMetadata(5L, 150L, 3, 200, 350);
        Document doc = Document.builder()
                .id("uuid-5-3")
                .text("Sample content")
                .score(0.88)
                .metadata(metadata)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        List<VectorHit> results = service.search("query", 150L, 10);

        VectorHit hit = results.get(0);
        assertNotNull(hit.id());
        assertNotNull(hit.content());
        assertNotNull(hit.documentId());
        assertNotNull(hit.groupId());
        assertNotNull(hit.chunkNumber());
        assertNotNull(hit.startPosition());
        assertNotNull(hit.endPosition());
        assertNotNull(hit.score());

        assertEquals(5L, hit.documentId());
        assertEquals(150L, hit.groupId());
        assertEquals(3, hit.chunkNumber());
        assertEquals(200, hit.startPosition());
        assertEquals(350, hit.endPosition());
    }

    @Test
    void testSearchIntegration_MixedDocumentTypes() {
        Map<String, Object> metadata1 = createMetadata(1L, 100L, 1, 0, 100);
        Map<String, Object> metadata2 = createMetadata(2L, 100L, 1, 0, 150);
        Map<String, Object> metadata3 = createMetadata(3L, 100L, 1, 0, 200);

        Document doc1 = Document.builder().id("doc-1").text("Doc 1").score(0.95).metadata(metadata1).build();
        Document doc2 = Document.builder().id("doc-2").text("Doc 2").score(0.85).metadata(metadata2).build();
        Document doc3 = Document.builder().id("doc-3").text("Doc 3").score(0.75).metadata(metadata3).build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc1, doc2, doc3));

        List<VectorHit> results = service.search("query", 100L, 10);

        assertEquals(3, results.size());
        assertEquals(1L, results.get(0).documentId());
        assertEquals(2L, results.get(1).documentId());
        assertEquals(3L, results.get(2).documentId());
    }

    @Test
    void testSearchIntegration_ResultsSortedByScore() {
        Map<String, Object> metadata1 = createMetadata(1L, 100L, 1, 0, 100);
        Map<String, Object> metadata2 = createMetadata(1L, 100L, 2, 100, 200);
        Map<String, Object> metadata3 = createMetadata(1L, 100L, 3, 200, 300);

        Document doc1 = Document.builder().id("doc-1").text("Content 1").score(0.75).metadata(metadata1).build();
        Document doc2 = Document.builder().id("doc-2").text("Content 2").score(0.95).metadata(metadata2).build();
        Document doc3 = Document.builder().id("doc-3").text("Content 3").score(0.85).metadata(metadata3).build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc2, doc3, doc1));

        List<VectorHit> results = service.search("query", 100L, 10);

        assertEquals(3, results.size());
        assertEquals(0.95, results.get(0).score());
        assertEquals(0.85, results.get(1).score());
        assertEquals(0.75, results.get(2).score());
    }

    @Test
    void testSearchIntegration_EmptyResultSet() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(Collections.emptyList());

        List<VectorHit> results = service.search("query", 100L, 10);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testSearchIntegration_CrossTenantViolationDetected() {
        Map<String, Object> metadata = createMetadata(1L, 200L, 1, 0, 100);
        Document doc = Document.builder()
                .id("doc-1")
                .text("Wrong tenant")
                .score(0.9)
                .metadata(metadata)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        VectorSearchException exception = assertThrows(VectorSearchException.class, () ->
                service.search("query", 100L, 10));

        assertTrue(exception.getMessage().contains("Cross-tenant violation"));
    }

    @Test
    void testSearchIntegration_LargeTopK() {
        List<Document> results = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            Map<String, Object> metadata = createMetadata(1L, 100L, i, 0, 100);
            results.add(Document.builder()
                    .id("doc-" + i)
                    .text("Content " + i)
                    .score(0.90)
                    .metadata(metadata)
                    .build());
        }

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(results);

        List<VectorHit> searchResults = service.search("query", 100L, 200);

        assertEquals(200, searchResults.size());
    }

    @Test
    void testSearchIntegration_WithNumericMetadataTypeConversion() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", 1);
        metadata.put("groupId", 100);
        metadata.put("chunkNumber", 5);
        metadata.put("startPosition", 50);
        metadata.put("endPosition", 150);

        Document doc = Document.builder()
                .id("doc-1")
                .text("Content")
                .score(0.92)
                .metadata(metadata)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        List<VectorHit> results = service.search("query", 100L, 10);

        VectorHit hit = results.get(0);
        assertEquals(1L, hit.documentId());
        assertEquals(100L, hit.groupId());
        assertEquals(5, hit.chunkNumber());
        assertEquals(50, hit.startPosition());
        assertEquals(150, hit.endPosition());
    }

    private Map<String, Object> createMetadata(Long documentId, Long groupId, Integer chunkNumber,
                                               Integer startPosition, Integer endPosition) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", documentId);
        metadata.put("groupId", groupId);
        metadata.put("chunkNumber", chunkNumber);
        metadata.put("startPosition", startPosition);
        metadata.put("endPosition", endPosition);
        return metadata;
    }
}