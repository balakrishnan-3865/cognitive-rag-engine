package com.skyshift.cognitiveragengine.workflows.claims.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.skyshift.cognitiveragengine.assistant.agent.AssistantReactAgentFactory;
import com.skyshift.cognitiveragengine.classifier.model.dto.IntentClassificationResponse;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;
import com.skyshift.cognitiveragengine.classifier.service.IntentClassifier;
import com.skyshift.cognitiveragengine.workflows.claims.state.AgentWorkflowState;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Exercises the compiled StateGraph end-to-end for all 3 {@link RoutingIntent} branches without a
 * Spring context - {@link IntentClassifier} and {@link AssistantReactAgentFactory} are mocked so
 * no real LLM/DB/Elasticsearch calls happen; only the graph wiring (docs/spec.md's 4-node,
 * 3-branch topology in {@link AgentGraphConfiguration}) is under test.
 */
@ExtendWith(MockitoExtension.class)
class AgentGraphConfigurationTest {

    @Mock
    private IntentClassifier intentClassifier;
    @Mock
    private AssistantReactAgentFactory assistantReactAgentFactory;
    @Mock
    private ReactAgent reactAgent;

    private CompiledGraph compiledGraph;

    @BeforeEach
    void setUp() throws GraphStateException {
        AgentGraphConfiguration configuration = new AgentGraphConfiguration();
        StateGraph stateGraph = configuration.claimsAgentGraph(intentClassifier, assistantReactAgentFactory);
        compiledGraph = configuration.claimsAgentCompiledGraph(stateGraph, new WorkflowProperties(8));
    }

    @Test
    void generalGreeting_routesToDirectChatCannedResponse() {
        when(intentClassifier.classify("Hello")).thenReturn(
                new IntentClassificationResponse(RoutingIntent.GENERAL_GREETING, 1.0, "greeting"));

        AgentWorkflowState result = invoke("Hello");

        assertTrue(result.finalAnswer().contains("Hello! I'm here to help"));
    }

    @Test
    void outOfScope_routesToOutOfScopeRefusal() {
        when(intentClassifier.classify("Tell me a joke")).thenReturn(
                new IntentClassificationResponse(RoutingIntent.OUT_OF_SCOPE, 1.0, "out of scope"));

        AgentWorkflowState result = invoke("Tell me a joke");

        assertTrue(result.finalAnswer().contains("I'm only able to help"));
    }

    @Test
    void agentQuery_routesToUnifiedReactAgentAndReturnsItsAnswer() {
        String query = "What's the status of claim CLM-123?";
        when(intentClassifier.classify(query)).thenReturn(
                new IntentClassificationResponse(RoutingIntent.AGENT_QUERY, 0.9, "agent query"));
        when(assistantReactAgentFactory.createAgent(any(), any(), any())).thenReturn(reactAgent);
        when(assistantReactAgentFactory.callWithErrorHandling(any(), anyList()))
                .thenReturn(new AssistantMessage("Claim CLM-123 is approved."));

        AgentWorkflowState result = invoke(query);

        assertEquals("Claim CLM-123 is approved.", result.finalAnswer());
        assertEquals(Boolean.TRUE, result.answered());
    }

    private AgentWorkflowState invoke(String query) {
        Map<String, Object> inputs = Map.of(
                WorkflowStateKeys.ORIGINAL_QUERY, query,
                WorkflowStateKeys.GROUP_ID, 1L,
                WorkflowStateKeys.USER_ID, 2L
        );
        Optional<OverAllState> result = compiledGraph.invoke(inputs);
        assertTrue(result.isPresent());
        return new AgentWorkflowState(result.get());
    }
}
