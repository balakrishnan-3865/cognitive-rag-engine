package com.skyshift.cognitiveragengine.qa.service;

import com.skyshift.cognitiveragengine.qa.config.QaProperties;
import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the thin documentId pass-through added to retrieveDocuments(): QaService's direct call
 * path (not the Query/advisor path) must forward an optional documentId straight into
 * HybridChunkRetrievalService without altering groupId/topK behavior.
 */
@ExtendWith(MockitoExtension.class)
class ReadyChunkDocumentRetrieverTest {

    @Mock
    private HybridChunkRetrievalService hybridChunkRetrievalService;

    private ReadyChunkDocumentRetriever retriever;

    @BeforeEach
    void setUp() {
        QaProperties qaProperties = new QaProperties();
        qaProperties.setTopK(7);
        retriever = new ReadyChunkDocumentRetriever(hybridChunkRetrievalService, qaProperties);
    }

    @Test
    void retrieveDocuments_withDocumentId_forwardsDocumentIdAndTopKToHybridRetrieval() {
        DocumentBundle expected = new DocumentBundle(List.of());
        when(hybridChunkRetrievalService.retrieveRelevantChunks("question", 100L, 42L, 7)).thenReturn(expected);

        DocumentBundle actual = retriever.retrieveDocuments(100L, 42L, "question");

        assertEquals(expected, actual);
        verify(hybridChunkRetrievalService).retrieveRelevantChunks("question", 100L, 42L, 7);
    }

    @Test
    void retrieveDocuments_withoutDocumentId_forwardsNullDocumentId() {
        DocumentBundle expected = new DocumentBundle(List.of());
        when(hybridChunkRetrievalService.retrieveRelevantChunks("question", 100L, null, 7)).thenReturn(expected);

        DocumentBundle actual = retriever.retrieveDocuments(100L, "question");

        assertEquals(expected, actual);
        verify(hybridChunkRetrievalService).retrieveRelevantChunks("question", 100L, null, 7);
    }
}
