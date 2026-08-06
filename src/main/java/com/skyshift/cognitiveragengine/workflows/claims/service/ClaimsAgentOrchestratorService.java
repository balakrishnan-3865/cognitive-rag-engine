package com.skyshift.cognitiveragengine.workflows.claims.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.skyshift.cognitiveragengine.workflows.claims.model.dto.AssistantQueryResponse;
import com.skyshift.cognitiveragengine.workflows.claims.state.ReflectionResult;
import com.skyshift.cognitiveragengine.workflows.claims.state.SubqueryResult;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Single-shot entry point into the claims agent graph (docs/spec.md §0, §5). No conversationId, no
 * memory read/write - each call builds a fresh input Map and lets {@link CompiledGraph#invoke(Map)}
 * build a brand-new {@link OverAllState} per request (never the {@code invoke(OverAllState, ...)}
 * overload with a reused instance - see docs/spec.md §1.1).
 */
@Slf4j
@Service
public class ClaimsAgentOrchestratorService {

    private final CompiledGraph claimsAgentCompiledGraph;

    public ClaimsAgentOrchestratorService(CompiledGraph claimsAgentCompiledGraph) {
        this.claimsAgentCompiledGraph = claimsAgentCompiledGraph;
    }

    public AssistantQueryResponse query(String query, Long groupId, Long userId) {
        String traceId = UUID.randomUUID().toString();
        log.info("Processing claims query: traceId={}, groupId={}", traceId, groupId);

        Map<String, Object> inputs = Map.of(
                WorkflowStateKeys.ORIGINAL_QUERY, query,
                WorkflowStateKeys.GROUP_ID, groupId,
                WorkflowStateKeys.USER_ID, userId
        );

        OverAllState finalState = claimsAgentCompiledGraph.invoke(inputs)
                .orElseThrow(() -> new IllegalStateException("Claims agent graph produced no final state, traceId=" + traceId));

        String finalAnswer = finalState.value(WorkflowStateKeys.FINAL_ANSWER, "");
        ReflectionResult reflectionResult = finalState.value(WorkflowStateKeys.REFLECTION_RESULT, (ReflectionResult) null);
        List<SubqueryResult> subqueryResults = finalState.value(WorkflowStateKeys.SUBQUERY_RESULTS, List.of());

        if (!subqueryResults.isEmpty() && subqueryResults.stream().allMatch(SubqueryResult::failed)) {
            String reasonMessage = "Unable to retrieve the information needed to answer this question: "
                    + subqueryResults.stream()
                            .map(SubqueryResult::failureReason)
                            .filter(Objects::nonNull)
                            .distinct()
                            .collect(Collectors.joining("; "));
            log.warn("Claims query failed - all subqueries failed: traceId={}", traceId);
            return new AssistantQueryResponse(false, reasonMessage, finalAnswer);
        }

        if (reflectionResult != null) {
            if (reflectionResult.grounded()) {
                log.info("Claims query answered and grounded: traceId={}", traceId);
                return new AssistantQueryResponse(true, null, finalAnswer);
            }
            log.info("Claims query answered but ungrounded: traceId={}, reason={}", traceId, reflectionResult.reason());
            return new AssistantQueryResponse(false, reflectionResult.reason(), finalAnswer);
        }

        log.info("Claims query answered via non-RAG path: traceId={}", traceId);
        return new AssistantQueryResponse(true, null, finalAnswer);
    }
}
