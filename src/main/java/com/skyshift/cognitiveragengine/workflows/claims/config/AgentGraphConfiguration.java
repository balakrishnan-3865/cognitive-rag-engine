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
import com.skyshift.cognitiveragengine.tools.ClaimStatusTool;
import com.skyshift.cognitiveragengine.workflows.claims.node.AnswerSynthesisNode;
import com.skyshift.cognitiveragengine.workflows.claims.node.ClaimStatusDirectNode;
import com.skyshift.cognitiveragengine.workflows.claims.node.DirectChatNode;
import com.skyshift.cognitiveragengine.workflows.claims.node.IntentCheckNode;
import com.skyshift.cognitiveragengine.workflows.claims.node.OutOfScopeNode;
import com.skyshift.cognitiveragengine.workflows.claims.node.QueryPlannerNode;
import com.skyshift.cognitiveragengine.workflows.claims.node.ReflectionNode;
import com.skyshift.cognitiveragengine.workflows.claims.node.SubqueryLoopExecutorNode;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Builds and compiles the claims agent orchestrator's StateGraph (docs/spec.md §2, §4): eight
 * nodes wired per the topology diagram, compiled once into a {@link CompiledGraph} singleton at
 * startup. Request-time state, not graph structure, varies per call - see
 * {@link com.skyshift.cognitiveragengine.workflows.claims.service.ClaimsAgentOrchestratorService}.
 */
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
public class AgentGraphConfiguration {

    @Bean
    public StateGraph claimsAgentGraph(
            ChatClient.Builder chatClientBuilder,
            IntentClassifier intentClassifier,
            ClaimStatusTool claimStatusTool,
            AssistantReactAgentFactory assistantReactAgentFactory,
            WorkflowProperties workflowProperties
    ) throws GraphStateException {

        // Built locally rather than as its own @Bean, so it doesn't register a second ambiguous
        // ChatClient candidate alongside qaChatClient in the application context.
        ChatClient claimsAgentChatClient = chatClientBuilder.build();

        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategy(WorkflowStateKeys.ORIGINAL_QUERY, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.GROUP_ID, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.USER_ID, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.ROUTING_INTENT, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.SUBQUERIES, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.CURRENT_SUBQUERY_INDEX, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.SUBQUERY_RESULTS, KeyStrategy.APPEND)
                .addStrategy(WorkflowStateKeys.REFLECTION_RESULT, KeyStrategy.REPLACE)
                .addStrategy(WorkflowStateKeys.FINAL_ANSWER, KeyStrategy.REPLACE)
                .build();

        IntentCheckNode intentCheckNode = new IntentCheckNode(intentClassifier);
        ClaimStatusDirectNode claimStatusDirectNode = new ClaimStatusDirectNode(claimsAgentChatClient, claimStatusTool);
        QueryPlannerNode queryPlannerNode = new QueryPlannerNode(claimsAgentChatClient, workflowProperties);
        SubqueryLoopExecutorNode subqueryLoopExecutorNode = new SubqueryLoopExecutorNode(assistantReactAgentFactory);
        ReflectionNode reflectionNode = new ReflectionNode(claimsAgentChatClient);
        AnswerSynthesisNode answerSynthesisNode = new AnswerSynthesisNode(claimsAgentChatClient);
        DirectChatNode directChatNode = new DirectChatNode(claimsAgentChatClient);
        OutOfScopeNode outOfScopeNode = new OutOfScopeNode();

        return new StateGraph(keyStrategyFactory)
                .addNode(IntentCheckNode.NAME, node_async(intentCheckNode))
                .addNode(ClaimStatusDirectNode.NAME, node_async(claimStatusDirectNode))
                .addNode(QueryPlannerNode.NAME, node_async(queryPlannerNode))
                .addNode(SubqueryLoopExecutorNode.NAME, node_async(subqueryLoopExecutorNode))
                .addNode(ReflectionNode.NAME, node_async(reflectionNode))
                .addNode(AnswerSynthesisNode.NAME, node_async(answerSynthesisNode))
                .addNode(DirectChatNode.NAME, node_async(directChatNode))
                .addNode(OutOfScopeNode.NAME, node_async(outOfScopeNode))

                .addEdge(StateGraph.START, IntentCheckNode.NAME)
                .addConditionalEdges(IntentCheckNode.NAME,
                        edge_async(state -> state
                                .value(WorkflowStateKeys.ROUTING_INTENT, RoutingIntent.OUT_OF_SCOPE)
                                .name()),
                        Map.of(
                                RoutingIntent.GENERAL_GREETING.name(), DirectChatNode.NAME,
                                RoutingIntent.OUT_OF_SCOPE.name(), OutOfScopeNode.NAME,
                                RoutingIntent.CLAIM_STATUS_TOOL.name(), ClaimStatusDirectNode.NAME,
                                RoutingIntent.POLICY_DOCUMENT_RAG.name(), QueryPlannerNode.NAME,
                                RoutingIntent.COMPLEX_MULTI_SOURCE.name(), QueryPlannerNode.NAME
                        ))
                .addEdge(QueryPlannerNode.NAME, SubqueryLoopExecutorNode.NAME)
                .addConditionalEdges(SubqueryLoopExecutorNode.NAME,
                        edge_async(state -> {
                            List<?> subqueries = state.value(WorkflowStateKeys.SUBQUERIES, List.of());
                            int currentIndex = state.value(WorkflowStateKeys.CURRENT_SUBQUERY_INDEX, 0);
                            return currentIndex < subqueries.size() ? "loop" : "done";
                        }),
                        Map.of(
                                "loop", SubqueryLoopExecutorNode.NAME,
                                "done", ReflectionNode.NAME
                        ))
                .addEdge(ReflectionNode.NAME, AnswerSynthesisNode.NAME)
                .addEdge(AnswerSynthesisNode.NAME, StateGraph.END)
                .addEdge(DirectChatNode.NAME, StateGraph.END)
                .addEdge(OutOfScopeNode.NAME, StateGraph.END)
                .addEdge(ClaimStatusDirectNode.NAME, StateGraph.END);
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
