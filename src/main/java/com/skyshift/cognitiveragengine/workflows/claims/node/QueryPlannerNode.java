package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.skyshift.cognitiveragengine.workflows.claims.config.WorkflowProperties;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reserved for queries that reach it via POLICY_DOCUMENT_RAG or COMPLEX_MULTI_SOURCE. Performs one
 * of three behaviors rather than pure decomposition: passthrough (query is already specific),
 * rewrite (intent is clear but the query is vague), or split (genuinely multi-part / needs both a
 * claims-status and a policy-document lookup), capped at {@code workflow.max-subqueries}
 * (docs/spec.md §3.3).
 */
@Slf4j
public class QueryPlannerNode implements NodeAction {

    public static final String NAME = "query_planner";

    private static final String SYSTEM_PROMPT = """
            You are a query planning assistant for an insurance claims and policy support system.
            Given the customer's query, decide how it should be handled before retrieval:
            - PASSTHROUGH: the query is already specific and well-formed. Return it unchanged as the only subquery.
            - REWRITE: the intent is clear but the query is vague or underspecified. Return a single, more specific rewritten query as the only subquery.
            - SPLIT: the query is genuinely multi-part, or needs both a claims-status lookup and a policy-document lookup. Decompose it into focused subqueries, at most %d. If the natural decomposition would exceed this limit, keep only the most load-bearing subqueries rather than truncating an arbitrary suffix.
            Respond with ONLY valid JSON (no markdown, no extra text):
            {
              "subqueries": ["<subquery 1>", "<subquery 2>"]
            }
            """;

    private final ChatClient chatClient;
    private final WorkflowProperties workflowProperties;

    public QueryPlannerNode(ChatClient chatClient, WorkflowProperties workflowProperties) {
        this.chatClient = chatClient;
        this.workflowProperties = workflowProperties;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String originalQuery = state.value(WorkflowStateKeys.ORIGINAL_QUERY, "");
        int maxSubqueries = workflowProperties.maxSubqueries();

        QueryPlanResponse response = chatClient.prompt()
                .system(SYSTEM_PROMPT.formatted(maxSubqueries))
                .user(originalQuery)
                .call()
                .entity(QueryPlanResponse.class);

        List<String> subqueries = (response == null || response.subqueries() == null || response.subqueries().isEmpty())
                ? List.of(originalQuery)
                : response.subqueries().stream().limit(maxSubqueries).toList();

        log.info("query_planner produced {} subquery(ies)", subqueries.size());

        Map<String, Object> result = new HashMap<>();
        result.put(WorkflowStateKeys.SUBQUERIES, subqueries);
        result.put(WorkflowStateKeys.CURRENT_SUBQUERY_INDEX, 0);
        return result;
    }
}
