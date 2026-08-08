package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.skyshift.cognitiveragengine.assistant.agent.AssistantReactAgentFactory;
import com.skyshift.cognitiveragengine.common.exception.MalformedToolCallException;
import com.skyshift.cognitiveragengine.common.exception.RecursionLimitExceededException;
import com.skyshift.cognitiveragengine.common.exception.ToolExecutionTimeoutException;
import com.skyshift.cognitiveragengine.common.exception.UncategorizedAgentException;
import com.skyshift.cognitiveragengine.workflows.claims.state.AgentWorkflowState;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single ReAct agent node handling every AGENT_QUERY request - policy-document lookups,
 * claim-status lookups, and multi-source queries alike - via one tool-calling loop bound to both
 * KnowledgeBaseTool and ClaimStatusTool (docs/spec.md, "UnifiedReactAgentNode.java (new)"). Sends
 * the original query only - no subquery decomposition. Signals failure via the same categorized
 * exceptions {@link AssistantReactAgentFactory#callWithErrorHandling} already throws, rather than
 * an additional LLM grounding call.
 */
@Slf4j
public class UnifiedReactAgentNode implements NodeAction {

    public static final String NAME = "unified_react_agent";

    private static final String FALLBACK_ANSWER =
            "I wasn't able to process this request. Please try rephrasing your question or try again shortly.";

    private final AssistantReactAgentFactory assistantReactAgentFactory;

    public UnifiedReactAgentNode(AssistantReactAgentFactory assistantReactAgentFactory) {
        this.assistantReactAgentFactory = assistantReactAgentFactory;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        AgentWorkflowState workflowState = new AgentWorkflowState(state);
        String originalQuery = workflowState.originalQuery();
        Long groupId = workflowState.groupId();
        Long userId = workflowState.userId();

        List<Document> retrievedDocuments = new ArrayList<>();
        Map<String, Object> result = new HashMap<>();

        try {
            ReactAgent agent = assistantReactAgentFactory.createAgent(groupId, userId, retrievedDocuments);
            List<Message> messages = List.of(new UserMessage(originalQuery));
            AssistantMessage response = assistantReactAgentFactory.callWithErrorHandling(agent, messages);

            result.put(WorkflowStateKeys.FINAL_ANSWER, response.getText());
            result.put(WorkflowStateKeys.ANSWERED, true);
            log.info("unified_react_agent answered successfully for groupId={}", groupId);
        } catch (MalformedToolCallException | RecursionLimitExceededException
                | ToolExecutionTimeoutException | UncategorizedAgentException e) {
            log.warn("unified_react_agent failed for groupId={}: {}", groupId, e.getMessage(), e);
            result.put(WorkflowStateKeys.FINAL_ANSWER, FALLBACK_ANSWER);
            result.put(WorkflowStateKeys.ANSWERED, false);
            result.put(WorkflowStateKeys.FAILURE_REASON, e.getMessage());
        }

        return result;
    }
}
