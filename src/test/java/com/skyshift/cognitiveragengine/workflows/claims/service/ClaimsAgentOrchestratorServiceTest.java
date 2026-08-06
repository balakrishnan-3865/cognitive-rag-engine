package com.skyshift.cognitiveragengine.workflows.claims.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateBuilder;
import com.skyshift.cognitiveragengine.workflows.claims.model.dto.AssistantQueryResponse;
import com.skyshift.cognitiveragengine.workflows.claims.state.ReflectionResult;
import com.skyshift.cognitiveragengine.workflows.claims.state.SubqueryResult;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimsAgentOrchestratorServiceTest {

    private static final Long GROUP_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final String QUERY = "What's the status of my claim?";
    private static final String FINAL_ANSWER = "Your claim is approved.";

    @Mock
    private CompiledGraph claimsAgentCompiledGraph;

    private ClaimsAgentOrchestratorService serviceWithMockGraph() {
        return new ClaimsAgentOrchestratorService(claimsAgentCompiledGraph);
    }

    @Test
    void groundedReflection_answersTrue() {
        OverAllState state = stateWith(Map.of(
                WorkflowStateKeys.FINAL_ANSWER, FINAL_ANSWER,
                WorkflowStateKeys.REFLECTION_RESULT, new ReflectionResult(true, "Fully grounded.")
        ));
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenReturn(Optional.of(state));

        AssistantQueryResponse response = serviceWithMockGraph().query(QUERY, GROUP_ID, USER_ID);

        assertTrue(response.answered());
        assertNull(response.reasonMessage());
        assertEquals(FINAL_ANSWER, response.answer());
    }

    @Test
    void ungroundedReflection_answersFalseButKeepsAnswerText() {
        OverAllState state = stateWith(Map.of(
                WorkflowStateKeys.FINAL_ANSWER, FINAL_ANSWER,
                WorkflowStateKeys.REFLECTION_RESULT, new ReflectionResult(false, "Missing supporting evidence.")
        ));
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenReturn(Optional.of(state));

        AssistantQueryResponse response = serviceWithMockGraph().query(QUERY, GROUP_ID, USER_ID);

        assertFalse(response.answered());
        assertEquals("Missing supporting evidence.", response.reasonMessage());
        assertEquals(FINAL_ANSWER, response.answer());
    }

    @Test
    void absentReflectionResult_directChatPath_answersTrue() {
        OverAllState state = stateWith(Map.of(
                WorkflowStateKeys.FINAL_ANSWER, "Hello! How can I help you today?"
        ));
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenReturn(Optional.of(state));

        AssistantQueryResponse response = serviceWithMockGraph().query("Hi there", GROUP_ID, USER_ID);

        assertTrue(response.answered());
        assertNull(response.reasonMessage());
    }

    @Test
    void allSubqueriesFailed_answersFalseRegardlessOfReflection() {
        SubqueryResult failedResult = new SubqueryResult("sub-query-1", "", List.of(), true, "Tool execution timed out");
        OverAllState state = stateWith(Map.of(
                WorkflowStateKeys.FINAL_ANSWER, "",
                WorkflowStateKeys.SUBQUERY_RESULTS, List.of(failedResult),
                WorkflowStateKeys.REFLECTION_RESULT, new ReflectionResult(true, "false positive - should not win")
        ));
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenReturn(Optional.of(state));

        AssistantQueryResponse response = serviceWithMockGraph().query(QUERY, GROUP_ID, USER_ID);

        assertFalse(response.answered());
        assertTrue(response.reasonMessage().contains("Tool execution timed out"));
    }

    @Test
    void graphProducesNoFinalState_throwsIllegalStateException() {
        when(claimsAgentCompiledGraph.invoke(anyMap())).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> serviceWithMockGraph().query(QUERY, GROUP_ID, USER_ID));
    }

    private static OverAllState stateWith(Map<String, Object> data) {
        OverAllStateBuilder builder = OverAllStateBuilder.builder().withData(data);
        for (String key : data.keySet()) {
            builder.withKeyStrategy(key, KeyStrategy.REPLACE);
        }
        return builder.build();
    }
}
