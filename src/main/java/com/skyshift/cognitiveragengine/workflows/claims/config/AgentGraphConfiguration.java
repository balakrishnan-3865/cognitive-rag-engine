package com.skyshift.cognitiveragengine.workflows.claims.config;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.skyshift.cognitiveragengine.assistant.agent.AssistantReactAgentFactory;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;
import com.skyshift.cognitiveragengine.classifier.service.IntentClassifier;
import com.skyshift.cognitiveragengine.workflows.claims.node.DirectChatNode;
import com.skyshift.cognitiveragengine.workflows.claims.node.IntentCheckNode;
import com.skyshift.cognitiveragengine.workflows.claims.node.OutOfScopeNode;
import com.skyshift.cognitiveragengine.workflows.claims.node.UnifiedReactAgentNode;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Builds and compiles the claims agent orchestrator's StateGraph (docs/spec.md): four nodes wired
 * per the simplified topology - intent_check routes to direct_chat, out_of_scope, or
 * unified_react_agent - compiled once into a {@link CompiledGraph} singleton at startup.
 * Request-time state, not graph structure, varies per call - see
 * {@link com.skyshift.cognitiveragengine.workflows.claims.service.ClaimsAgentOrchestratorService}.
 */
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
public class AgentGraphConfiguration {

    @Bean
    public StateGraph claimsAgentGraph(
            IntentClassifier intentClassifier,
            AssistantReactAgentFactory assistantReactAgentFactory
    ) throws GraphStateException {

        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategy(WorkflowStateKeys.ORIGINAL_QUERY, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.GROUP_ID, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.USER_ID, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.ROUTING_INTENT, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.FINAL_ANSWER, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.ANSWERED, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.FAILURE_REASON, KeyStrategy.REPLACE)
                .build();

        IntentCheckNode intentCheckNode = new IntentCheckNode(intentClassifier);
        DirectChatNode directChatNode = new DirectChatNode();
        OutOfScopeNode outOfScopeNode = new OutOfScopeNode();
        UnifiedReactAgentNode unifiedReactAgentNode = new UnifiedReactAgentNode(assistantReactAgentFactory);

        return new StateGraph(keyStrategyFactory)
                .addNode(IntentCheckNode.NAME, node_async(intentCheckNode))
                .addNode(DirectChatNode.NAME, node_async(directChatNode))
                .addNode(OutOfScopeNode.NAME, node_async(outOfScopeNode))
                .addNode(UnifiedReactAgentNode.NAME, node_async(unifiedReactAgentNode))

                .addEdge(StateGraph.START, IntentCheckNode.NAME)
                .addConditionalEdges(IntentCheckNode.NAME,
                        edge_async(state -> state
                                .value(WorkflowStateKeys.ROUTING_INTENT, RoutingIntent.OUT_OF_SCOPE)
                                .name()),
                        Map.of(
                                RoutingIntent.GENERAL_GREETING.name(), DirectChatNode.NAME,
                                RoutingIntent.OUT_OF_SCOPE.name(), OutOfScopeNode.NAME,
                                RoutingIntent.AGENT_QUERY.name(), UnifiedReactAgentNode.NAME
                        ))
                .addEdge(DirectChatNode.NAME, StateGraph.END)
                .addEdge(OutOfScopeNode.NAME, StateGraph.END)
                .addEdge(UnifiedReactAgentNode.NAME, StateGraph.END);
    }

    @Bean
    public CompiledGraph claimsAgentCompiledGraph(
            StateGraph claimsAgentGraph,
            WorkflowProperties workflowProperties
    ) throws GraphStateException {
        CompileConfig compileConfig = CompileConfig.builder()
                .recursionLimit(workflowProperties.graphRecursionLimit())
                .build();
        return claimsAgentGraph.compile(compileConfig);
    }
}
