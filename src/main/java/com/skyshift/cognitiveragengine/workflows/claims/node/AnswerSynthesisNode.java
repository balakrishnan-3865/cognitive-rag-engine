package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.skyshift.cognitiveragengine.workflows.claims.state.ReflectionResult;
import com.skyshift.cognitiveragengine.workflows.claims.state.SubqueryResult;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Combines the accumulated {@link SubqueryResult}s into one coherent, conversational answer, and
 * appends a caveat when {@code reflection_check} found the answer ungrounded (docs/spec.md §3, §7).
 */
@Slf4j
public class AnswerSynthesisNode implements NodeAction {

    public static final String NAME = "answer_synthesis";

    private static final String SYSTEM_PROMPT = """
            You are an insurance claims and policy assistant. Combine the answers gathered for each
            subquery of the customer's original question into a single, coherent, conversational answer.
            Do not mention subqueries or internal processing - answer as if responding directly to the customer.
            """;

    private static final String GROUNDING_CAVEAT =
            "\n\nNote: some details in this answer could not be fully verified against the retrieved information.";

    private final ChatClient chatClient;

    public AnswerSynthesisNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String originalQuery = state.value(WorkflowStateKeys.ORIGINAL_QUERY, "");
        List<SubqueryResult> subqueryResults = state.value(WorkflowStateKeys.SUBQUERY_RESULTS, List.of());
        ReflectionResult reflectionResult = state.value(WorkflowStateKeys.REFLECTION_RESULT, (ReflectionResult) null);

        String evidence = subqueryResults.stream()
                .filter(r -> !r.failed())
                .map(r -> "Q: %s\nA: %s".formatted(r.subquery(), r.answerText()))
                .collect(Collectors.joining("\n\n"));

        String userPrompt = "Original question: %s\n\nSubquery answers:\n%s".formatted(
                originalQuery, evidence.isBlank() ? "(no subquery produced a usable answer)" : evidence);

        String synthesized = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        if (reflectionResult != null && !reflectionResult.grounded()) {
            synthesized = synthesized + GROUNDING_CAVEAT;
        }

        log.info("answer_synthesis produced final answer, grounded={}",
                reflectionResult == null ? "n/a" : reflectionResult.grounded());

        return Map.of(WorkflowStateKeys.FINAL_ANSWER, synthesized);
    }
}
