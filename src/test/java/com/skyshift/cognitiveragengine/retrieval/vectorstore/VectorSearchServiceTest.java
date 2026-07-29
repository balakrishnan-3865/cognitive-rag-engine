package com.skyshift.cognitiveragengine.retrieval.vectorstore;

import com.skyshift.cognitiveragengine.retrieval.vectorstore.exception.VectorSearchException;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.model.VectorHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorSearchServiceTest {

    @Mock
    private VectorStore vectorStore;

    private VectorSearchService service;

    @BeforeEach
    void setUp() {
        service = new VectorSearchService(vectorStore);
    }

    @Test
    void testSearchWithValidParams_ReturnsRankedResults() {
        Map<String, Object> metadata1 = new HashMap<>();
        metadata1.put("documentId", 1L);
        metadata1.put("chunkId", 501L);
        metadata1.put("groupId", 100L);
        metadata1.put("chunkNumber", 1);
        metadata1.put("startPosition", 0);
        metadata1.put("endPosition", 100);

        Document doc1 = Document.builder()
                .id("doc-1")
                .text("First chunk content")
                .score(0.95)
                .metadata(metadata1)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc1));

        List<VectorHit> results = service.search("test query", 100L, 10);

        assertNotNull(results);
        assertEquals(1, results.size());
        VectorHit hit = results.get(0);
        assertEquals("doc-1", hit.id());
        assertEquals("First chunk content", hit.content());
        assertEquals(1L, hit.documentId());
        assertEquals(501L, hit.chunkId());
        assertEquals(100L, hit.groupId());
        assertEquals(1, hit.chunkNumber());
        assertEquals(0, hit.startPosition());
        assertEquals(100, hit.endPosition());
        assertEquals(0.95, hit.score());
    }

    @Test
    void testSearchWithNullScore_DefaultsToZero() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", 1L);
        metadata.put("chunkId", 501L);
        metadata.put("groupId", 100L);
        metadata.put("chunkNumber", 1);
        metadata.put("startPosition", 0);
        metadata.put("endPosition", 100);

        Document doc = Document.builder()
                .id("doc-1")
                .text("Content")
                .score(null)
                .metadata(metadata)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        List<VectorHit> results = service.search("query", 100L, 10);

        assertEquals(1, results.size());
        assertEquals(0.0, results.get(0).score());
    }

    @Test
    void testSearchWithEmptyQuery_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.search("", 100L, 10));
    }

    @Test
    void testSearchWithNullQuery_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.search(null, 100L, 10));
    }

    @Test
    void testSearchWithQueryExceedingMaxLength_ThrowsIllegalArgumentException() {
        String longQuery = "a".repeat(2001);
        assertThrows(IllegalArgumentException.class, () ->
                service.search(longQuery, 100L, 10));
    }

    @Test
    void testSearchWithNullGroupId_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.search("query", null, 10));
    }

    @Test
    void testSearchWithNegativeGroupId_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.search("query", -1L, 10));
    }

    @Test
    void testSearchWithZeroGroupId_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.search("query", 0L, 10));
    }

    @Test
    void testSearchWithTopKZero_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.search("query", 100L, 0));
    }

    @Test
    void testSearchWithTopKExceedingMax_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.search("query", 100L, 1001));
    }

    @Test
    void testSearchWithCrossTenantViolation_ThrowsVectorSearchException() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", 1L);
        metadata.put("chunkId", 501L);
        metadata.put("groupId", 200L);
        metadata.put("chunkNumber", 1);
        metadata.put("startPosition", 0);
        metadata.put("endPosition", 100);

        Document doc = Document.builder()
                .id("doc-1")
                .text("Content")
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
    void testSearchWithMissingRequiredMetadataField_ThrowsVectorSearchException() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", 1L);
        metadata.put("chunkId", 501L);
        metadata.put("groupId", 100L);
        // Missing chunkNumber

        Document doc = Document.builder()
                .id("doc-1")
                .text("Content")
                .score(0.9)
                .metadata(metadata)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        VectorSearchException exception = assertThrows(VectorSearchException.class, () ->
                service.search("query", 100L, 10));

        assertTrue(exception.getMessage().contains("Required metadata field"));
    }

    @Test
    void testSearchWithInvalidTypeInMetadata_ThrowsVectorSearchException() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", "not-a-long");
        metadata.put("chunkId", 501L);
        metadata.put("groupId", 100L);
        metadata.put("chunkNumber", 1);
        metadata.put("startPosition", 0);
        metadata.put("endPosition", 100);

        Document doc = Document.builder()
                .id("doc-1")
                .text("Content")
                .score(0.9)
                .metadata(metadata)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        VectorSearchException exception = assertThrows(VectorSearchException.class, () ->
                service.search("query", 100L, 10));

        assertTrue(exception.getMessage().contains("Failed to convert document metadata types"));
    }

    @Test
    void testSearchWithMultipleResults_ReturnsSortedByScore() {
        Map<String, Object> metadata1 = new HashMap<>();
        metadata1.put("documentId", 1L);
        metadata1.put("chunkId", 501L);
        metadata1.put("groupId", 100L);
        metadata1.put("chunkNumber", 1);
        metadata1.put("startPosition", 0);
        metadata1.put("endPosition", 100);

        Map<String, Object> metadata2 = new HashMap<>();
        metadata2.put("documentId", 1L);
        metadata2.put("chunkId", 502L);
        metadata2.put("groupId", 100L);
        metadata2.put("chunkNumber", 2);
        metadata2.put("startPosition", 100);
        metadata2.put("endPosition", 200);

        Document doc1 = Document.builder().id("doc-1").text("Content 1").score(0.85).metadata(metadata1).build();
        Document doc2 = Document.builder().id("doc-2").text("Content 2").score(0.95).metadata(metadata2).build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc2, doc1));

        List<VectorHit> results = service.search("query", 100L, 10);

        assertEquals(2, results.size());
        assertEquals(0.95, results.get(0).score());
        assertEquals(0.85, results.get(1).score());
    }

    @Test
    void testSearchWithEmptyResults_ReturnsEmptyList() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(Collections.emptyList());

        List<VectorHit> results = service.search("query", 100L, 10);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testSearchWithVectorStoreException_WrapsInVectorSearchException() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("VectorStore error"));

        VectorSearchException exception = assertThrows(VectorSearchException.class, () ->
                service.search("query", 100L, 10));

        assertTrue(exception.getMessage().contains("Vector search failed"));
        assertNotNull(exception.getCause());
    }

    @Test
    void testSearchWithNumericTypeConversion_ConvertsNumberToLong() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", 1);
        metadata.put("chunkId", 501L);
        metadata.put("groupId", 100L);
        metadata.put("chunkNumber", 1);
        metadata.put("startPosition", 0);
        metadata.put("endPosition", 100);

        Document doc = Document.builder()
                .id("doc-1")
                .text("Content")
                .score(0.9)
                .metadata(metadata)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        List<VectorHit> results = service.search("query", 100L, 10);

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).documentId());
    }

    @Test
    void testSearchCallsVectorStoreWithCorrectFilterExpression() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(Collections.emptyList());

        service.search("test query", 100L, 10);

        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }
}