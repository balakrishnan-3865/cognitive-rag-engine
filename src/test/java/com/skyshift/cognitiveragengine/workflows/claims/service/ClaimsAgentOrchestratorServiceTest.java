package com.skyshift.cognitiveragengine.workflows.claims.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateBuilder;
import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.document.service.DocumentService;
import com.skyshift.cognitiveragengine.qa.model.SourceChunk;
import com.skyshift.cognitiveragengine.workflows.claims.model.dto.AssistantQueryResponse;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimsAgentOrchestratorServiceTest {

    private static final Long GROUP_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final String QUERY = "What's the status of my claim?";
    private static final String FINAL_ANSWER = "Your claim is approved.";

    @Mock
    private CompiledGraph claimsAgentCompiledGraph;

    @Mock
    private DocumentService documentService;

    private ClaimsAgentOrchestratorService serviceWithMockGraph() {
        return new ClaimsAgentOrchestratorService(claimsAgentCompiledGraph, documentService);
    }

    @Test
    void unifiedReactAgentAnswered_answersTrue() {
        OverAllState state = stateWith(Map.of(
                WorkflowStateKeys.FINAL_ANSWER, FINAL_ANSWER,
                WorkflowStateKeys.ANSWERED, true
        ));
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenReturn(Optional.of(state));

        AssistantQueryResponse response = serviceWithMockGraph().query(QUERY, GROUP_ID, USER_ID, null);

        assertTrue(response.answered());
        assertNull(response.reasonMessage());
        assertEquals(FINAL_ANSWER, response.answer());
        assertEquals(List.of(), response.sources());
    }

    @Test
    void unifiedReactAgentFailed_answersFalseWithReason() {
        OverAllState state = stateWith(Map.of(
                WorkflowStateKeys.FINAL_ANSWER, "I wasn't able to process this request. Please try rephrasing your question or try again shortly.",
                WorkflowStateKeys.ANSWERED, false,
                WorkflowStateKeys.FAILURE_REASON, "Tool execution timed out"
        ));
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenReturn(Optional.of(state));

        AssistantQueryResponse response = serviceWithMockGraph().query(QUERY, GROUP_ID, USER_ID, null);

        assertFalse(response.answered());
        assertEquals("Tool execution timed out", response.reasonMessage());
        assertEquals(List.of(), response.sources());
    }

    @Test
    void absentAnsweredKey_directChatPath_answersTrue() {
        OverAllState state = stateWith(Map.of(
                WorkflowStateKeys.FINAL_ANSWER, "Hello! How can I help you today?"
        ));
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenReturn(Optional.of(state));

        AssistantQueryResponse response = serviceWithMockGraph().query("Hi there", GROUP_ID, USER_ID, null);

        assertTrue(response.answered());
        assertNull(response.reasonMessage());
        assertEquals(List.of(), response.sources());
    }

    @Test
    void graphProducesNoFinalState_returnsGracefulFailureResponse() {
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenReturn(Optional.empty());

        AssistantQueryResponse response = serviceWithMockGraph().query(QUERY, GROUP_ID, USER_ID, null);

        assertFalse(response.answered());
        assertEquals("Unable to process this request.", response.reasonMessage());
        assertNull(response.answer());
        assertEquals(List.of(), response.sources());
    }

    @Test
    void graphInvocationThrows_returnsGracefulFailureResponseInsteadOfPropagating() {
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenThrow(new RuntimeException("graph engine failure"));

        AssistantQueryResponse response = serviceWithMockGraph().query(QUERY, GROUP_ID, USER_ID, null);

        assertFalse(response.answered());
        assertEquals("Unable to process this request.", response.reasonMessage());
        assertNull(response.answer());
        assertEquals(List.of(), response.sources());
    }

    @Test
    void unifiedReactAgentAnswered_withSources_returnsThemLikeQaAndAssistant() {
        List<SourceChunk> sources = List.of(new SourceChunk("policy text", 5L, 42L, 1, 0.91, "hybrid"));
        OverAllState state = stateWith(Map.of(
                WorkflowStateKeys.FINAL_ANSWER, FINAL_ANSWER,
                WorkflowStateKeys.ANSWERED, true,
                WorkflowStateKeys.SOURCES, sources
        ));
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenReturn(Optional.of(state));

        AssistantQueryResponse response = serviceWithMockGraph().query(QUERY, GROUP_ID, USER_ID, 42L);

        assertEquals(sources, response.sources());
    }

    @Test
    void invalidDocumentId_propagatesBusinessExceptionAsClearRejection_insteadOfGracefulFailure() {
        when(documentService.resolveSearchableDocumentIds(GROUP_ID, 999L))
                .thenThrow(new BusinessException("Document not found or not ready: documentId=999"));

        assertThrows(BusinessException.class,
                () -> serviceWithMockGraph().query(QUERY, GROUP_ID, USER_ID, 999L));

        verifyNoInteractions(claimsAgentCompiledGraph);
    }

    private static OverAllState stateWith(Map<String, Object> data) {
        OverAllStateBuilder builder = OverAllStateBuilder.builder().withData(data);
        for (String key : data.keySet()) {
            builder.withKeyStrategy(key, KeyStrategy.REPLACE);
        }
        return builder.build();
    }
}
