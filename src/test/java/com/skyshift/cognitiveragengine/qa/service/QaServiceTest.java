package com.skyshift.cognitiveragengine.qa.service;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.qa.config.QaProperties;
import com.skyshift.cognitiveragengine.qa.exception.RetrievalException;
import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import com.skyshift.cognitiveragengine.qa.model.KnowledgeSourceResponse;
import com.skyshift.cognitiveragengine.qa.model.dto.QAResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers how QaService.askQuestion reacts to its ReadyChunkDocumentRetriever collaborator,
 * specifically for the optional documentId scoping path: an invalid/inaccessible documentId
 * must surface as a clear rejection (BusinessException escaping to GlobalExceptionHandler -> 400),
 * not get swallowed by the broad catch-all into a soft "answered: false" 200 response the way
 * genuine retrieval infrastructure failures are. Also covers the ChatClient.entity(...) call
 * itself failing (e.g. malformed LLM JSON), which folds into the same soft "answered: false"
 * response rather than propagating. ChatClient/PromptTemplate are left unstubbed in the tests
 * that short-circuit before reaching the chat call.
 */
@ExtendWith(MockitoExtension.class)
class QaServiceTest {

    @Mock
    private ReadyChunkDocumentRetriever readyChunkDocumentRetriever;

    @Mock
    private ChatClient chatClient;

    @Mock
    private PromptTemplate qaQueryPromptTemplate;

    private QaService qaService;

    @BeforeEach
    void setUp() {
        QaProperties qaProperties = new QaProperties();
        qaService = new QaService(
                readyChunkDocumentRetriever,
                chatClient,
                qaQueryPromptTemplate,
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

    @Test
    void askQuestion_chatClientEntityCallFails_returnsUnansweredResponseNotException() {
        when(readyChunkDocumentRetriever.retrieveDocuments(eq(100L), isNull(), eq("query")))
                .thenReturn(new DocumentBundle(List.of(new Document("chunk text"))));
        when(qaQueryPromptTemplate.render(anyMap())).thenReturn("rendered prompt");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt("rendered prompt")).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.responseEntity(KnowledgeSourceResponse.class))
                .thenThrow(new RuntimeException("Could not parse the given text to the desired target type"));

        QAResponse response = qaService.askQuestion("query", 100L, null);

        assertFalse(response.answered());
        assertTrue(response.reasonMessage().contains("Failed to process question"));
        assertTrue(response.sources().isEmpty());
    }

    @Test
    void askQuestion_chatClientEntityCallSucceeds_returnsAnsweredResponse() {
        when(readyChunkDocumentRetriever.retrieveDocuments(eq(100L), isNull(), eq("query")))
                .thenReturn(new DocumentBundle(List.of(new Document("chunk text"))));
        when(qaQueryPromptTemplate.render(anyMap())).thenReturn("rendered prompt");

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<ChatResponse, KnowledgeSourceResponse> responseEntity = mock(ResponseEntity.class);
        KnowledgeSourceResponse knowledgeSourceResponse = new KnowledgeSourceResponse(true, "the answer");
        when(responseEntity.entity()).thenReturn(knowledgeSourceResponse);
        when(responseEntity.response()).thenReturn(null);
        when(chatClient.prompt("rendered prompt")).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.responseEntity(KnowledgeSourceResponse.class)).thenReturn(responseEntity);

        QAResponse response = qaService.askQuestion("query", 100L, null);

        assertTrue(response.answered());
        assertEquals("the answer", response.answer());
        assertEquals(1, response.sources().size());
    }
}
