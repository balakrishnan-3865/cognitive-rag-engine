package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.skyshift.cognitiveragengine.workflows.claims.state.ReflectionResult;
import com.skyshift.cognitiveragengine.workflows.claims.state.SubqueryResult;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Single-pass grounding evaluation over the accumulated {@link SubqueryResult}s. Its verdict is no
 * longer log-only - it flows to {@code answer_synthesis} and drives the orchestrator's
 * answered/reasonMessage fields (docs/spec.md §1.4, §7).
 */
@Slf4j
public class ReflectionNode implements NodeAction {

    public static final String NAME = "reflection_check";

    private static final String SYSTEM_PROMPT = """
            You are a grounding evaluator for an insurance claims assistant. Given the original
            customer question and the answers gathered for each of its subqueries, determine whether
            the gathered answers are sufficient and grounded to answer the original question.
            Respond with ONLY valid JSON (no markdown, no extra text):
            {
              "grounded": <true|false>,
              "reason": "<brief explanation>"
            }
            """;

    private final ChatClient chatClient;

    public ReflectionNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String originalQuery = state.value(WorkflowStateKeys.ORIGINAL_QUERY, "");
        List<SubqueryResult> subqueryResults = state.value(WorkflowStateKeys.SUBQUERY_RESULTS, List.of());

        String evidence = subqueryResults.stream()
                .map(r -> "Subquery: %s\nAnswer: %s\nFailed: %s".formatted(r.subquery(), r.answerText(), r.failed()))
                .collect(Collectors.joining("\n\n"));

        String userPrompt = "Original question: %s\n\nGathered subquery results:\n%s".formatted(originalQuery, evidence);

        ReflectionResult reflectionResult;
        try {
            reflectionResult = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .entity(ReflectionResult.class);
            if (reflectionResult == null) {
                reflectionResult = new ReflectionResult(false, "Grounding evaluation returned no result.");
            }
        } catch (Exception e) {
            log.warn("reflection_check evaluation failed: {}", e.getMessage(), e);
            reflectionResult = new ReflectionResult(false, "Grounding evaluation failed: " + e.getMessage());
        }

        log.info("reflection_check verdict: grounded={}", reflectionResult.grounded());

        Map<String, Object> result = new HashMap<>();
        result.put(WorkflowStateKeys.REFLECTION_RESULT, reflectionResult);
        return result;
    }
}
