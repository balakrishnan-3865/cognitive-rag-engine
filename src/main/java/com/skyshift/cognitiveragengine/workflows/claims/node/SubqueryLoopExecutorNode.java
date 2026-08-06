package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.skyshift.cognitiveragengine.assistant.agent.AssistantReactAgentFactory;
import com.skyshift.cognitiveragengine.common.exception.MalformedToolCallException;
import com.skyshift.cognitiveragengine.common.exception.RecursionLimitExceededException;
import com.skyshift.cognitiveragengine.common.exception.ToolExecutionTimeoutException;
import com.skyshift.cognitiveragengine.workflows.claims.state.SubqueryResult;
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
 * Orchestrates one serial pass of the subquery execution loop. Builds a fresh {@link ReactAgent}
 * per pass (no reuse across passes), passes both the original query and the current subquery so
 * the agent retains parent context, and reads retrieved documents back from the mutable
 * out-parameter list {@code KnowledgeBaseTool} appends into via {@code ToolContext} - not from the
 * returned {@link AssistantMessage} (docs/spec.md §3.1). A failed pass is recorded, not fatal to
 * the request.
 */
@Slf4j
public class SubqueryLoopExecutorNode implements NodeAction {

    public static final String NAME = "subquery_loop_executor";

    private final AssistantReactAgentFactory assistantReactAgentFactory;

    public SubqueryLoopExecutorNode(AssistantReactAgentFactory assistantReactAgentFactory) {
        this.assistantReactAgentFactory = assistantReactAgentFactory;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String originalQuery = state.value(WorkflowStateKeys.ORIGINAL_QUERY, "");
        List<String> subqueries = state.value(WorkflowStateKeys.SUBQUERIES, List.of());
        int currentIndex = state.value(WorkflowStateKeys.CURRENT_SUBQUERY_INDEX, 0);
        Long groupId = state.value(WorkflowStateKeys.GROUP_ID, (Long) null);
        Long userId = state.value(WorkflowStateKeys.USER_ID, (Long) null);

        String subquery = subqueries.get(currentIndex);
        List<Document> retrievedDocuments = new ArrayList<>();

        SubqueryResult subqueryResult;
        try {
            ReactAgent agent = assistantReactAgentFactory.createAgent(groupId, userId, retrievedDocuments);
            List<Message> messages = List.of(
                    new UserMessage("Original question: " + originalQuery),
                    new UserMessage("Subquery to answer: " + subquery)
            );
            AssistantMessage response = assistantReactAgentFactory.callWithErrorHandling(agent, messages);

            subqueryResult = new SubqueryResult(subquery, response.getText(), retrievedDocuments, false, null);
        } catch (MalformedToolCallException | RecursionLimitExceededException | ToolExecutionTimeoutException e) {
            log.warn("subquery_loop_executor failed on subquery '{}': {}", subquery, e.getMessage(), e);
            subqueryResult = new SubqueryResult(subquery, "", List.of(), true, e.getMessage());
        }

        log.info("subquery_loop_executor completed pass {}/{}", currentIndex + 1, subqueries.size());

        Map<String, Object> result = new HashMap<>();
        result.put(WorkflowStateKeys.SUBQUERY_RESULTS, List.of(subqueryResult));
        result.put(WorkflowStateKeys.CURRENT_SUBQUERY_INDEX, currentIndex + 1);
        return result;
    }
}
