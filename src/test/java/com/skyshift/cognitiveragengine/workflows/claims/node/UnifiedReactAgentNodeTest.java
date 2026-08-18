package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateBuilder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.skyshift.cognitiveragengine.assistant.agent.AssistantReactAgentFactory;
import com.skyshift.cognitiveragengine.common.exception.MalformedToolCallException;
import com.skyshift.cognitiveragengine.common.exception.RecursionLimitExceededException;
import com.skyshift.cognitiveragengine.common.exception.ToolExecutionTimeoutException;
import com.skyshift.cognitiveragengine.common.exception.UncategorizedAgentException;
import com.skyshift.cognitiveragengine.qa.model.SourceChunk;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedReactAgentNodeTest {

    private static final Long GROUP_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final String QUERY = "What's the status of claim CLM-123, and does my plan cover physical therapy?";

    @Mock
    private AssistantReactAgentFactory assistantReactAgentFactory;

    @Mock
    private ReactAgent reactAgent;

    private UnifiedReactAgentNode node;

    @BeforeEach
    void setUp() {
        node = new UnifiedReactAgentNode(assistantReactAgentFactory);
    }

    @Test
    void successfulCall_setsFinalAnswerAndAnsweredTrue() {
        AssistantMessage response = new AssistantMessage("Claim CLM-123 is approved and physical therapy is covered.");
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), isNull(), any())).thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList())).thenReturn(response);

        Map<String, Object> result = node.apply(stateWith(QUERY, GROUP_ID, USER_ID, null));

        assertEquals(response.getText(), result.get(WorkflowStateKeys.FINAL_ANSWER));
        assertEquals(Boolean.TRUE, result.get(WorkflowStateKeys.ANSWERED));
        assertFalse(result.containsKey(WorkflowStateKeys.FAILURE_REASON));
    }

    @Test
    void delegatesAgentCreationToFactory_withGroupAndUserContext() {
        // UnifiedReactAgentNode doesn't bind tools itself - AssistantReactAgentFactory.createAgent(...)
        // does (KnowledgeBaseTool + ClaimStatusTool), and that binding is out of scope for this
        // refactor (docs/spec.md's "Cannot change" constraint). This only confirms the node
        // delegates to the factory with the right per-request context.
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), isNull(), any())).thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList()))
                .thenReturn(new AssistantMessage("answer"));

        node.apply(stateWith(QUERY, GROUP_ID, USER_ID, null));

        verify(assistantReactAgentFactory).createAgent(eq(GROUP_ID), eq(USER_ID), isNull(), any());
    }

    @Test
    void documentIdInState_isPassedThroughToFactory() {
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), eq(99L), any())).thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList()))
                .thenReturn(new AssistantMessage("answer"));

        node.apply(stateWith(QUERY, GROUP_ID, USER_ID, 99L));

        verify(assistantReactAgentFactory).createAgent(eq(GROUP_ID), eq(USER_ID), eq(99L), any());
    }

    @Test
    void successfulCall_withRetrievedDocuments_populatesSourcesInResult() {
        Document retrieved = new Document("policy text", Map.of(
                "chunkId", 7L, "documentId", 99L, "chunkNumber", 2, "similarity", 0.88, "source", "hybrid"));
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), eq(99L), any()))
                .thenAnswer(invocation -> {
                    List<Document> retrievedDocuments = invocation.getArgument(3);
                    retrievedDocuments.add(retrieved);
                    return reactAgent;
                });
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList()))
                .thenReturn(new AssistantMessage("answer"));

        Map<String, Object> result = node.apply(stateWith(QUERY, GROUP_ID, USER_ID, 99L));

        @SuppressWarnings("unchecked")
        List<SourceChunk> sources = (List<SourceChunk>) result.get(WorkflowStateKeys.SOURCES);
        assertEquals(1, sources.size());
        assertEquals(99L, sources.get(0).documentId());
        assertEquals(7L, sources.get(0).chunkId());
    }

    @Test
    void successfulCall_noRetrievedDocuments_sourcesIsEmptyList() {
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), isNull(), any())).thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList()))
                .thenReturn(new AssistantMessage("answer"));

        Map<String, Object> result = node.apply(stateWith(QUERY, GROUP_ID, USER_ID, null));

        assertEquals(List.of(), result.get(WorkflowStateKeys.SOURCES));
    }

    @ParameterizedTest
    @MethodSource("categorizedExceptions")
    void categorizedException_setsAnsweredFalseWithFailureReason(RuntimeException exception) {
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), isNull(), any())).thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList())).thenThrow(exception);

        Map<String, Object> result = node.apply(stateWith(QUERY, GROUP_ID, USER_ID, null));

        assertEquals(Boolean.FALSE, result.get(WorkflowStateKeys.ANSWERED));
        assertEquals(exception.getMessage(), result.get(WorkflowStateKeys.FAILURE_REASON));
        assertTrue(((String) result.get(WorkflowStateKeys.FINAL_ANSWER)).contains("wasn't able to process"));
        assertFalse(result.containsKey(WorkflowStateKeys.SOURCES));
    }

    @Test
    void maskedExceptionText_isTreatedAsFailure_notSuccess() {
        // spring-ai-alibaba-agent-framework's AgentLlmNode.apply() catches every model-call
        // exception and returns a normal-looking AssistantMessage with this exact literal text
        // instead of rethrowing - callWithErrorHandling never sees an exception to catch.
        AssistantMessage maskedResponse = new AssistantMessage("Exception: 429 quota exceeded");
        when(assistantReactAgentFactory.createAgent(eq(GROUP_ID), eq(USER_ID), isNull(), any())).thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(eq(reactAgent), anyList())).thenReturn(maskedResponse);

        Map<String, Object> result = node.apply(stateWith(QUERY, GROUP_ID, USER_ID, null));

        assertEquals(Boolean.FALSE, result.get(WorkflowStateKeys.ANSWERED));
        assertEquals(maskedResponse.getText(), result.get(WorkflowStateKeys.FAILURE_REASON));
        assertTrue(((String) result.get(WorkflowStateKeys.FINAL_ANSWER)).contains("wasn't able to process"));
        assertFalse(result.containsKey(WorkflowStateKeys.SOURCES));
    }

    private static Stream<RuntimeException> categorizedExceptions() {
        return Stream.of(
                new MalformedToolCallException("Tool 'unknownTool' not found"),
                new RecursionLimitExceededException("recursion limit exceeded"),
                new ToolExecutionTimeoutException("tool execution timeout"),
                new UncategorizedAgentException("unexpected downstream failure")
        );
    }

    private static OverAllState stateWith(String query, Long groupId, Long userId, Long documentId) {
        Map<String, Object> data = new HashMap<>();
        data.put(WorkflowStateKeys.ORIGINAL_QUERY, query);
        data.put(WorkflowStateKeys.GROUP_ID, groupId);
        data.put(WorkflowStateKeys.USER_ID, userId);
        if (documentId != null) {
            data.put(WorkflowStateKeys.DOCUMENT_ID, documentId);
        }
        OverAllStateBuilder builder = OverAllStateBuilder.builder().withData(data);
        for (String key : data.keySet()) {
            builder.withKeyStrategy(key, KeyStrategy.REPLACE);
        }
        builder.withKeyStrategy(WorkflowStateKeys.SOURCES, KeyStrategy.REPLACE);
        return builder.build();
    }
}
