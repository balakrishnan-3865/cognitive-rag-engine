package com.skyshift.cognitiveragengine.assistant.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.skyshift.cognitiveragengine.assistant.agent.AssistantReactAgentFactory;
import com.skyshift.cognitiveragengine.assistant.config.AssistantProperties;
import com.skyshift.cognitiveragengine.assistant.model.dto.AssistantResponse;
import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.common.exception.MalformedToolCallException;
import com.skyshift.cognitiveragengine.common.exception.RecursionLimitExceededException;
import com.skyshift.cognitiveragengine.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the documentId validation added to ask(): an invalid/inaccessible documentId must be a
 * clear rejection (BusinessException escaping to GlobalExceptionHandler -> 400), raised before any
 * conversation is created or the ReactAgent is invoked - mirrors QaServiceTest's equivalent case.
 */
@ExtendWith(MockitoExtension.class)
class AssistantServiceTest {

    private static final Long GROUP_ID = 100L;
    private static final Long USER_ID = 7L;

    @Mock
    private AssistantReactAgentFactory assistantReactAgentFactory;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ConversationSummaryService conversationSummaryService;

    @Mock
    private DocumentService documentService;

    @Mock
    private ReactAgent reactAgent;

    private AssistantService assistantService;

    @BeforeEach
    void setUp() {
        assistantService = new AssistantService(
                assistantReactAgentFactory,
                conversationService,
                conversationSummaryService,
                documentService,
                new AssistantProperties()
        );
    }

    @Test
    void ask_invalidDocumentId_propagatesBusinessExceptionAsClearRejection() {
        when(documentService.resolveSearchableDocumentIds(GROUP_ID, 999L))
                .thenThrow(new BusinessException("Document not found or not ready: documentId=999"));

        assertThrows(BusinessException.class,
                () -> assistantService.ask("what does this document say?", GROUP_ID, USER_ID, null, 999L));

        verifyNoInteractions(conversationService, assistantReactAgentFactory);
    }

    @Test
    void repairInstruction_listsAllRegisteredTools_notJustSearchKnowledgeBase() throws Exception {
        when(documentService.resolveSearchableDocumentIds(GROUP_ID, null)).thenReturn(Collections.emptyList());
        when(conversationService.getOrCreateConversation(null, GROUP_ID)).thenReturn(1L);
        when(conversationService.loadHistory(1L, 10)).thenReturn(Collections.emptyList());
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), eq(null), anyList()))
                .thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList()))
                .thenThrow(new MalformedToolCallException("No ToolCallback found for tool name: fakeTool", null));
        when(assistantReactAgentFactory.registeredToolNames())
                .thenReturn(List.of("searchKnowledgeBase", "getClaims"));

        ArgumentCaptor<List<Message>> retryMessagesCaptor = ArgumentCaptor.captor();
        when(reactAgent.call(retryMessagesCaptor.capture()))
                .thenReturn(new AssistantMessage("recovered answer"));

        assistantService.ask("what's my claim status?", GROUP_ID, USER_ID, null, null);

        String repairInstruction = retryMessagesCaptor.getValue().stream()
                .filter(UserMessage.class::isInstance)
                .reduce((first, second) -> second)
                .map(Message::getText)
                .orElseThrow();

        assertTrue(repairInstruction.contains("searchKnowledgeBase"), "repair instruction should mention searchKnowledgeBase");
        assertTrue(repairInstruction.contains("getClaims"), "repair instruction should mention getClaims");
    }

    @Test
    void ask_maskedExceptionText_isTreatedAsFailure_notSuccess() {
        // spring-ai-alibaba-agent-framework's AgentLlmNode.apply() catches every model-call
        // exception and returns a normal-looking AssistantMessage with this exact literal text
        // instead of rethrowing - callWithErrorHandling never sees an exception to catch.
        when(documentService.resolveSearchableDocumentIds(GROUP_ID, null)).thenReturn(Collections.emptyList());
        when(conversationService.getOrCreateConversation(null, GROUP_ID)).thenReturn(1L);
        when(conversationService.loadHistory(1L, 10)).thenReturn(Collections.emptyList());
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), eq(null), anyList()))
                .thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList()))
                .thenReturn(new AssistantMessage("Exception: 429 quota exceeded"));

        AssistantResponse response = assistantService.ask("what's my claim status?", GROUP_ID, USER_ID, null, null);

        assertFalse(response.answered());
        verifyNoInteractions(conversationSummaryService);
    }

    @Test
    void repairLoop_maskedExceptionText_isTreatedAsFailure_notSuccess() throws Exception {
        when(documentService.resolveSearchableDocumentIds(GROUP_ID, null)).thenReturn(Collections.emptyList());
        when(conversationService.getOrCreateConversation(null, GROUP_ID)).thenReturn(1L);
        when(conversationService.loadHistory(1L, 10)).thenReturn(Collections.emptyList());
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), eq(null), anyList()))
                .thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList()))
                .thenThrow(new MalformedToolCallException("No ToolCallback found for tool name: fakeTool", null));
        when(assistantReactAgentFactory.registeredToolNames()).thenReturn(List.of("searchKnowledgeBase"));
        when(reactAgent.call(anyList())).thenReturn(new AssistantMessage("Exception: 429 quota exceeded"));

        AssistantResponse response = assistantService.ask("what's my claim status?", GROUP_ID, USER_ID, null, null);

        assertFalse(response.answered());
    }

    @Test
    void ask_modelCallLimitExceeded_returnsGracefulDegradationResponse() {
        when(documentService.resolveSearchableDocumentIds(GROUP_ID, null)).thenReturn(Collections.emptyList());
        when(conversationService.getOrCreateConversation(null, GROUP_ID)).thenReturn(1L);
        when(conversationService.loadHistory(1L, 10)).thenReturn(Collections.emptyList());
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), eq(null), anyList()))
                .thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList()))
                .thenThrow(new RecursionLimitExceededException("Model call limits exceeded: run limit (3/3)"));

        AssistantResponse response = assistantService.ask("what's my claim status?", GROUP_ID, USER_ID, null, null);

        assertFalse(response.answered());
        assertEquals("Request exceeded maximum reasoning depth. Please ask a more focused question.", response.reasonMessage());
    }
}
