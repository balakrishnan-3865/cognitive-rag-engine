package com.skyshift.cognitiveragengine.workflows.claims.state;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateBuilder;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentWorkflowStateTest {

    @Test
    void readsAllPopulatedKeys() {
        AgentWorkflowState state = wrap(Map.of(
                WorkflowStateKeys.ORIGINAL_QUERY, "What's my deductible?",
                WorkflowStateKeys.GROUP_ID, 10L,
                WorkflowStateKeys.USER_ID, 20L,
                WorkflowStateKeys.ROUTING_INTENT, RoutingIntent.AGENT_QUERY,
                WorkflowStateKeys.FINAL_ANSWER, "Your deductible is $500.",
                WorkflowStateKeys.ANSWERED, true,
                WorkflowStateKeys.FAILURE_REASON, "unused when answered"
        ));

        assertEquals("What's my deductible?", state.originalQuery());
        assertEquals(10L, state.groupId());
        assertEquals(20L, state.userId());
        assertEquals(RoutingIntent.AGENT_QUERY, state.routingIntent());
        assertEquals("Your deductible is $500.", state.finalAnswer());
        assertEquals(Boolean.TRUE, state.answered());
        assertEquals("unused when answered", state.failureReason());
    }

    @Test
    void missingKeys_fallBackToDefaults() {
        AgentWorkflowState state = wrap(Map.of());

        assertEquals("", state.originalQuery());
        assertNull(state.groupId());
        assertNull(state.userId());
        assertNull(state.routingIntent());
        assertEquals("", state.finalAnswer());
        assertNull(state.answered());
        assertNull(state.failureReason());
    }

    @Test
    void failurePath_answeredFalseWithReason() {
        AgentWorkflowState state = wrap(Map.of(
                WorkflowStateKeys.ANSWERED, false,
                WorkflowStateKeys.FAILURE_REASON, "Tool execution timed out"
        ));

        assertEquals(Boolean.FALSE, state.answered());
        assertEquals("Tool execution timed out", state.failureReason());
    }

    private static AgentWorkflowState wrap(Map<String, Object> data) {
        OverAllStateBuilder builder = OverAllStateBuilder.builder().withData(data);
        for (String key : data.keySet()) {
            builder.withKeyStrategy(key, KeyStrategy.REPLACE);
        }
        return new AgentWorkflowState(builder.build());
    }
}
