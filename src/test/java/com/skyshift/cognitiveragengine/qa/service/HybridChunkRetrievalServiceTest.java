package com.skyshift.cognitiveragengine.qa.service;

import com.skyshift.cognitiveragengine.qa.config.RetrievalProperties;
import com.skyshift.cognitiveragengine.qa.exception.RetrievalException;
import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.model.KeywordHit;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.service.ElasticsearchChunkIndexService;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.VectorSearchService;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.model.VectorHit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HybridChunkRetrievalServiceTest {

    private static final int DENSE_POOL_SIZE = 20;
    private static final int SPARSE_POOL_SIZE = 20;

    @Mock
    private VectorSearchService vectorSearchService;

    @Mock
    private ElasticsearchChunkIndexService elasticsearchChunkIndexService;

    private RetrievalProperties retrievalProperties;
    private HybridChunkRetrievalService service;

    @BeforeEach
    void setUp() {
        retrievalProperties = new RetrievalProperties();
        retrievalProperties.getDense().setTopK(DENSE_POOL_SIZE);
        retrievalProperties.getSparse().setTopK(SPARSE_POOL_SIZE);
        retrievalProperties.getFusion().setRrfK(60);
        service = new HybridChunkRetrievalService(
                vectorSearchService,
                elasticsearchChunkIndexService,
                retrievalProperties,
                ObservationRegistry.NOOP,
                new SimpleMeterRegistry()
        );
    }

    // ========== SUCCESS CASES ==========

    @Test
    void testRetrieveRelevantChunks_BothSourcesSucceed_UsesHybridRanking() throws IOException {
        List<VectorHit> denseHits = List.of(
                vectorHit(10L, 1L, "First dense chunk"),
                vectorHit(10L, 2L, "Second dense chunk")
        );
        List<KeywordHit> sparseHits = List.of(
                keywordHit(10L, 2L, "Second dense chunk"),
                keywordHit(10L, 3L, "Sparse-only chunk")
        );

        when(vectorSearchService.search("query", 100L, DENSE_POOL_SIZE)).thenReturn(denseHits);
        when(elasticsearchChunkIndexService.searchChunks("query", 100L, SPARSE_POOL_SIZE)).thenReturn(sparseHits);

        DocumentBundle bundle = service.retrieveRelevantChunks("query", 100L, 5);

        List<Document> documents = bundle.documents();
        assertEquals(3, documents.size());
        assertEquals("2", documents.get(0).getMetadata().get("chunkNumber"));
        assertEquals("hybrid", documents.get(0).getMetadata().get("source"));
    }

    @Test
    void testRetrieveRelevantChunks_BothSourcesSucceed_TruncatesToRequestedTopK() throws IOException {
        List<VectorHit> denseHits = List.of(
                vectorHit(10L, 1L, "Chunk 1"),
                vectorHit(10L, 2L, "Chunk 2"),
                vectorHit(10L, 3L, "Chunk 3")
        );

        when(vectorSearchService.search("query", 100L, DENSE_POOL_SIZE)).thenReturn(denseHits);
        when(elasticsearchChunkIndexService.searchChunks("query", 100L, SPARSE_POOL_SIZE)).thenReturn(List.of());

        DocumentBundle bundle = service.retrieveRelevantChunks("query", 100L, 2);

        assertEquals(2, bundle.documents().size());
    }

    @Test
    void testRetrieveRelevantChunks_DenseWithEmptySparse_StillUseHybridRRF() throws IOException {
        List<VectorHit> denseHits = List.of(vectorHit(10L, 1L, "Dense chunk"));

        when(vectorSearchService.search("query", 100L, DENSE_POOL_SIZE)).thenReturn(denseHits);
        when(elasticsearchChunkIndexService.searchChunks("query", 100L, SPARSE_POOL_SIZE)).thenReturn(List.of());

        DocumentBundle bundle = service.retrieveRelevantChunks("query", 100L, 5);

        assertEquals(1, bundle.documents().size());
        assertEquals("Dense chunk", bundle.documents().get(0).getText());
        // Both sources succeeded (no exceptions), so source is "hybrid" even though sparse was empty
        // RRF natively handles one source being empty
        assertEquals("hybrid", bundle.documents().get(0).getMetadata().get("source"));
    }

    @Test
    void testRetrieveRelevantChunks_SparseOnly_WhenDenseFails() throws IOException {
        List<KeywordHit> sparseHits = List.of(
                keywordHit(10L, 1L, "Sparse-only chunk")
        );

        when(vectorSearchService.search("query", 100L, DENSE_POOL_SIZE))
                .thenThrow(new RuntimeException("Dense search failed"));
        when(elasticsearchChunkIndexService.searchChunks("query", 100L, SPARSE_POOL_SIZE))
                .thenReturn(sparseHits);

        DocumentBundle bundle = service.retrieveRelevantChunks("query", 100L, 5);

        assertEquals(1, bundle.documents().size());
        assertEquals("Sparse-only chunk", bundle.documents().get(0).getText());
        assertEquals("sparse", bundle.documents().get(0).getMetadata().get("source"));
    }

    @Test
    void testRetrieveRelevantChunks_NoChunks_IsNotAnError() throws IOException {
        when(vectorSearchService.search("query", 100L, DENSE_POOL_SIZE)).thenReturn(List.of());
        when(elasticsearchChunkIndexService.searchChunks("query", 100L, SPARSE_POOL_SIZE)).thenReturn(List.of());

        DocumentBundle bundle = service.retrieveRelevantChunks("query", 100L, 5);

        assertTrue(bundle.documents().isEmpty());
    }

    // ========== FAILURE CASES ==========

    @Test
    void testRetrieveRelevantChunks_BothFail_ThrowsRetrievalException() throws IOException {
        RuntimeException denseError = new RuntimeException("Dense connection failed");
        RuntimeException sparseError = new RuntimeException("Elasticsearch unavailable");

        when(vectorSearchService.search("query", 100L, DENSE_POOL_SIZE)).thenThrow(denseError);
        when(elasticsearchChunkIndexService.searchChunks("query", 100L, SPARSE_POOL_SIZE))
                .thenThrow(sparseError);

        RetrievalException exception = assertThrows(RetrievalException.class, () ->
                service.retrieveRelevantChunks("query", 100L, 5)
        );

        assertEquals("both", exception.getFailedSources());
        assertNotNull(exception.getDenseException());
        assertNotNull(exception.getSparseException());
    }

    // ========== METADATA TESTS ==========

    @Test
    void testRetrieveRelevantChunks_DocumentMetadataContract() throws IOException {
        List<VectorHit> denseHits = List.of(vectorHit(10L, 1L, "Content"));

        when(vectorSearchService.search("query", 100L, DENSE_POOL_SIZE)).thenReturn(denseHits);
        when(elasticsearchChunkIndexService.searchChunks("query", 100L, SPARSE_POOL_SIZE)).thenReturn(List.of());

        DocumentBundle bundle = service.retrieveRelevantChunks("query", 100L, 5);

        Document document = bundle.documents().get(0);
        assertEquals("1", document.getMetadata().get("chunkId"));
        assertEquals("10", document.getMetadata().get("documentId"));
        assertEquals("1", document.getMetadata().get("chunkNumber"));
        assertNotNull(document.getMetadata().get("similarity"));
        assertEquals("hybrid", document.getMetadata().get("source"));
    }

    @Test
    void testRetrieveRelevantChunks_HybridMetadata() throws IOException {
        List<VectorHit> denseHits = List.of(vectorHit(10L, 1L, "Content"));
        List<KeywordHit> sparseHits = List.of(keywordHit(10L, 1L, "Content"));

        when(vectorSearchService.search("query", 100L, DENSE_POOL_SIZE)).thenReturn(denseHits);
        when(elasticsearchChunkIndexService.searchChunks("query", 100L, SPARSE_POOL_SIZE)).thenReturn(sparseHits);

        DocumentBundle bundle = service.retrieveRelevantChunks("query", 100L, 5);

        Document document = bundle.documents().get(0);
        assertEquals("hybrid", document.getMetadata().get("source"));
    }

    // ========== HELPERS ==========

    private VectorHit vectorHit(Long documentId, Long chunkId, String content) {
        return new VectorHit("vec-" + chunkId, content, documentId, chunkId, 100L, chunkId.intValue(), 0.9);
    }

    private KeywordHit keywordHit(Long documentId, Long chunkId, String chunkText) {
        return new KeywordHit(documentId, chunkId, chunkId.intValue(), "file.pdf", chunkText, 12.5, 0.6);
    }
}