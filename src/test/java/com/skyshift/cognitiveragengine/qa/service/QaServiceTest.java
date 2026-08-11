package com.skyshift.cognitiveragengine.qa.service;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.qa.config.QaProperties;
import com.skyshift.cognitiveragengine.qa.exception.RetrievalException;
import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import com.skyshift.cognitiveragengine.qa.model.dto.QAResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers how QaService.askQuestion reacts to its ReadyChunkDocumentRetriever collaborator,
 * specifically for the optional documentId scoping path: an invalid/inaccessible documentId
 * must surface as a clear rejection (BusinessException escaping to GlobalExceptionHandler -> 400),
 * not get swallowed by the broad catch-all into a soft "answered: false" 200 response the way
 * genuine retrieval infrastructure failures are. ChatClient/PromptTemplate are unused in these
 * cases (both short-circuit before the chat call), so they're left unstubbed.
 */
@ExtendWith(MockitoExtension.class)
class QaServiceTest {

    @Mock
    private ReadyChunkDocumentRetriever readyChunkDocumentRetriever;

    @Mock
    private ChatClient chatClient;

    @Mock
    private PromptTemplate qaQueryPromptTemplate;

    @Mock
    private KnowledgeSourceResponseConverter responseConverter;

    private QaService qaService;

    @BeforeEach
    void setUp() {
        QaProperties qaProperties = new QaProperties();
        qaService = new QaService(
                readyChunkDocumentRetriever,
                chatClient,
                qaQueryPromptTemplate,
                responseConverter,
                qaProperties
        );
    }

    @Test
    void askQuestion_invalidDocumentId_propagatesBusinessExceptionAsClearRejection() {
        when(readyChunkDocumentRetriever.retrieveDocuments(eq(100L), eq(999L), eq("query")))
                .thenThrow(new BusinessException("Document not found or not ready: documentId=999"));

        assertThrows(BusinessException.class, () -> qaService.askQuestion("query", 100L, 999L));
    }

    @Test
    void askQuestion_nullDocumentId_stillDegradesGracefullyOnRetrievalInfraFailure() {
        RetrievalException infraFailure = new RetrievalException(
                new RuntimeException("dense down"), new RuntimeException("sparse down"));
        when(readyChunkDocumentRetriever.retrieveDocuments(eq(100L), isNull(), eq("query")))
                .thenThrow(infraFailure);

        QAResponse response = qaService.askQuestion("query", 100L, null);

        assertFalse(response.answered());
    }

    @Test
    void askQuestion_emptyDocumentBundle_returnsUnansweredResponseNotException() {
        when(readyChunkDocumentRetriever.retrieveDocuments(anyLong(), isNull(), eq("query")))
                .thenReturn(new DocumentBundle(List.of()));

        QAResponse response = qaService.askQuestion("query", 100L, null);

        assertFalse(response.answered());
        verifyNoInteractions(chatClient);
    }
}
