package com.skyshift.cognitiveragengine.retrieval.elasticsearch.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.ingestion.exception.NoChunksFoundException;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.model.KeywordHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Elasticsearch Chunk Index Service Integration Tests")
class ElasticsearchChunkIndexServiceTest {

    @Autowired
    private ElasticsearchChunkIndexService elasticsearchChunkIndexService;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    private static final Long TEST_GROUP_ID = 1L;
    private static final Long TEST_DOCUMENT_ID = 100L;
    private static final String TEST_FILE_NAME = "test-document.pdf";

    @BeforeEach
    void setUp() throws IOException {
        elasticsearchChunkIndexService.ensureIndexExists();
    }

    @Test
    @DisplayName("Index initialization should be idempotent and thread-safe")
    void testIndexInitialization() {
        assertDoesNotThrow(() -> {
            elasticsearchChunkIndexService.ensureIndexExists();
            elasticsearchChunkIndexService.ensureIndexExists();
        });
    }

    @Test
    @DisplayName("Should index a single chunk successfully")
    void testIndexSingleChunk() throws IOException, InterruptedException {
        DocumentChunkEntity chunk = DocumentChunkEntity.builder()
                .id(1L)
                .groupId(TEST_GROUP_ID)
                .documentId(TEST_DOCUMENT_ID)
                .chunkNumber(0)
                .chunkText("This is a test chunk about neural networks")
                .metadataJson("{}")
                .startPosition(0)
                .endPosition(43)
                .createdAt(LocalDateTime.now())
                .build();

        List<DocumentChunkEntity> chunks = List.of(chunk);
        assertDoesNotThrow(() -> elasticsearchChunkIndexService.indexChunks(TEST_DOCUMENT_ID, TEST_FILE_NAME, chunks));

        Thread.sleep(1000); // Wait for indexing
    }

    @Test
    @DisplayName("Should index multiple chunks in batches")
    void testIndexMultipleChunks() throws IOException, InterruptedException {
        List<DocumentChunkEntity> chunks = createTestChunks(75, TEST_GROUP_ID, TEST_DOCUMENT_ID);

        assertDoesNotThrow(() -> elasticsearchChunkIndexService.indexChunks(TEST_DOCUMENT_ID, TEST_FILE_NAME, chunks));

        Thread.sleep(1000);
    }

    @Test
    @DisplayName("Should validate chunk fields before indexing")
    void testChunkValidationFailure() {
        DocumentChunkEntity invalidChunk = DocumentChunkEntity.builder()
                .id(1L)
                .groupId(null) // Missing groupId
                .documentId(TEST_DOCUMENT_ID)
                .chunkNumber(0)
                .chunkText("Invalid chunk")
                .build();

        List<DocumentChunkEntity> chunks = List.of(invalidChunk);

        assertThrows(BusinessException.class,
                () -> elasticsearchChunkIndexService.indexChunks(TEST_DOCUMENT_ID, TEST_FILE_NAME, chunks),
                "Should throw BusinessException for missing required fields");
    }

    @Test
    @DisplayName("Should search chunks by query with groupId filter")
    void testSearchChunksWithGroupIdFilter() throws IOException, InterruptedException {
        Long groupId1 = 1L;
        Long groupId2 = 2L;
        Long docId1 = 100L;
        Long docId2 = 101L;

        List<DocumentChunkEntity> chunks1 = createTestChunks(3, groupId1, docId1);
        List<DocumentChunkEntity> chunks2 = createTestChunksWithOffset(3, groupId2, docId2, 100);

        elasticsearchChunkIndexService.indexChunks(docId1, "file1.pdf", chunks1);
        elasticsearchChunkIndexService.indexChunks(docId2, "file2.pdf", chunks2);

        Thread.sleep(1000);

        List<KeywordHit> results = elasticsearchChunkIndexService.searchChunks("test", groupId1, 10);

        assertTrue(results.size() > 0, "Should find chunks for groupId1");
        assertTrue(results.stream().allMatch(hit -> hit.rawScore() >= 0),
                "All results should have non-negative scores");
    }

    @Test
    @DisplayName("Should respect topK limit in search results")
    void testSearchChunksWithTopKLimit() throws IOException, InterruptedException {
        List<DocumentChunkEntity> chunks = createTestChunks(20, TEST_GROUP_ID, TEST_DOCUMENT_ID);
        elasticsearchChunkIndexService.indexChunks(TEST_DOCUMENT_ID, TEST_FILE_NAME, chunks);

        Thread.sleep(1000);

        List<KeywordHit> results = elasticsearchChunkIndexService.searchChunks("test", TEST_GROUP_ID, 5);

        assertTrue(results.size() <= 5, "Results should not exceed topK limit");
    }

    @Test
    @DisplayName("Should return empty results for invalid search parameters")
    void testSearchWithInvalidParameters() throws IOException {
        List<KeywordHit> emptyQuery = elasticsearchChunkIndexService.searchChunks("", TEST_GROUP_ID, 10);
        assertTrue(emptyQuery.isEmpty(), "Should return empty for empty query");

        List<KeywordHit> nullGroupId = elasticsearchChunkIndexService.searchChunks("test", null, 10);
        assertTrue(nullGroupId.isEmpty(), "Should return empty for null groupId");

        List<KeywordHit> invalidTopK = elasticsearchChunkIndexService.searchChunks("test", TEST_GROUP_ID, 0);
        assertTrue(invalidTopK.isEmpty(), "Should return empty for invalid topK");
    }

    @Test
    @DisplayName("Should delete chunks by documentId")
    void testDeleteChunksByDocumentId() throws IOException, InterruptedException {
        Long docIdToDelete = 200L;
        List<DocumentChunkEntity> chunks = createTestChunks(5, TEST_GROUP_ID, docIdToDelete);
        elasticsearchChunkIndexService.indexChunks(docIdToDelete, TEST_FILE_NAME, chunks);

        Thread.sleep(1000);

        assertDoesNotThrow(() -> elasticsearchChunkIndexService.deleteChunksByDocumentId(docIdToDelete));

        Thread.sleep(1000);
    }

    @Test
    @DisplayName("Should delete chunks by specific IDs")
    void testDeleteChunksByIds() throws IOException, InterruptedException {
        List<DocumentChunkEntity> chunks = createTestChunks(5, TEST_GROUP_ID, TEST_DOCUMENT_ID);
        elasticsearchChunkIndexService.indexChunks(TEST_DOCUMENT_ID, TEST_FILE_NAME, chunks);

        Thread.sleep(1000);

        List<String> idsToDelete = List.of("1", "2", "3");
        assertDoesNotThrow(() ->
                elasticsearchChunkIndexService.deleteChunksByIds("rag_sparse_chunks", idsToDelete));

        Thread.sleep(500);
    }

    @Test
    @DisplayName("Should handle empty chunk list gracefully")
    void testIndexEmptyChunkList() {
        List<DocumentChunkEntity> emptyChunks = new ArrayList<>();

        assertThrows(NoChunksFoundException.class,
                () -> elasticsearchChunkIndexService.indexChunks(TEST_DOCUMENT_ID, TEST_FILE_NAME, emptyChunks),
                "Should throw NoChunksFoundException for empty chunks list");
    }

    @Test
    @DisplayName("Should handle null chunk list gracefully")
    void testIndexNullChunkList() {
        assertThrows(NoChunksFoundException.class,
                () -> elasticsearchChunkIndexService.indexChunks(TEST_DOCUMENT_ID, TEST_FILE_NAME, null),
                "Should throw NoChunksFoundException for null chunks list");
    }

    @Test
    @DisplayName("Should normalize keyword scores to [0, 1] range")
    void testScoreNormalization() throws IOException, InterruptedException {
        List<DocumentChunkEntity> chunks = createTestChunks(5, TEST_GROUP_ID, TEST_DOCUMENT_ID);
        elasticsearchChunkIndexService.indexChunks(TEST_DOCUMENT_ID, TEST_FILE_NAME, chunks);

        Thread.sleep(1000);

        List<KeywordHit> results = elasticsearchChunkIndexService.searchChunks("test", TEST_GROUP_ID, 10);

        assertTrue(results.stream()
                        .allMatch(hit -> hit.normalizedScore() >= 0D && hit.normalizedScore() <= 1D),
                "All normalized scores should be in [0, 1] range");
    }

    @Test
    @DisplayName("Should handle search with null groupId")
    void testSearchWithNullGroupId() throws IOException {
        List<KeywordHit> results = elasticsearchChunkIndexService.searchChunks("test", null, 10);
        assertTrue(results.isEmpty(), "Should return empty results for null groupId");
    }

    @Test
    @DisplayName("Should not delete with null documentId")
    void testDeleteWithNullDocumentId() {
        assertDoesNotThrow(() -> elasticsearchChunkIndexService.deleteChunksByDocumentId(null));
    }

    @Test
    @DisplayName("Should handle batch indexing with validation errors")
    void testBatchIndexingWithValidationErrors() {
        List<DocumentChunkEntity> chunks = new ArrayList<>();
        chunks.add(createValidChunk(1L, TEST_GROUP_ID, TEST_DOCUMENT_ID, 0));
        chunks.add(DocumentChunkEntity.builder()
                .id(2L)
                .groupId(null) // Invalid
                .documentId(TEST_DOCUMENT_ID)
                .chunkNumber(1)
                .chunkText("Invalid chunk")
                .build());

        assertThrows(BusinessException.class,
                () -> elasticsearchChunkIndexService.indexChunks(TEST_DOCUMENT_ID, TEST_FILE_NAME, chunks),
                "Should throw BusinessException on validation failure");
    }

    @Test
    @DisplayName("Should search with special characters in query")
    void testSearchWithSpecialCharacters() throws IOException, InterruptedException {
        List<DocumentChunkEntity> chunks = createTestChunks(3, TEST_GROUP_ID, TEST_DOCUMENT_ID);
        elasticsearchChunkIndexService.indexChunks(TEST_DOCUMENT_ID, TEST_FILE_NAME, chunks);

        Thread.sleep(1000);

        assertDoesNotThrow(() ->
                elasticsearchChunkIndexService.searchChunks("test-chunk+data", TEST_GROUP_ID, 10));
    }

    @Test
    @DisplayName("Should handle concurrent index initialization")
    void testConcurrentIndexInitialization() {
        assertDoesNotThrow(() -> {
            Thread t1 = new Thread(() -> elasticsearchChunkIndexService.ensureIndexExists());
            Thread t2 = new Thread(() -> elasticsearchChunkIndexService.ensureIndexExists());
            Thread t3 = new Thread(() -> elasticsearchChunkIndexService.ensureIndexExists());

            t1.start();
            t2.start();
            t3.start();

            t1.join();
            t2.join();
            t3.join();
        });
    }

    // Helper methods

    private List<DocumentChunkEntity> createTestChunks(int count, Long groupId, Long documentId) {
        return createTestChunksWithOffset(count, groupId, documentId, 0);
    }

    private List<DocumentChunkEntity> createTestChunksWithOffset(int count, Long groupId, Long documentId, long idOffset) {
        List<DocumentChunkEntity> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            chunks.add(createValidChunk(idOffset + i, groupId, documentId, i));
        }
        return chunks;
    }

    private DocumentChunkEntity createValidChunk(Long chunkId, Long groupId, Long documentId, int chunkNumber) {
        return DocumentChunkEntity.builder()
                .id(chunkId)
                .groupId(groupId)
                .documentId(documentId)
                .chunkNumber(chunkNumber)
                .chunkText("This is test chunk number " + chunkNumber + " about machine learning and artificial intelligence")
                .metadataJson("{\"source\": \"test\"}")
                .startPosition(chunkNumber * 50)
                .endPosition((chunkNumber + 1) * 50)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}