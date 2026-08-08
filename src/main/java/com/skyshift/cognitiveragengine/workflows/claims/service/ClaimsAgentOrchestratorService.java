package com.skyshift.cognitiveragengine.workflows.claims.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.skyshift.cognitiveragengine.workflows.claims.model.dto.AssistantQueryResponse;
import com.skyshift.cognitiveragengine.workflows.claims.state.AgentWorkflowState;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Single-shot entry point into the claims agent graph (docs/spec.md). No conversationId, no
 * memory read/write - each call builds a fresh input Map and lets {@link CompiledGraph#invoke(Map)}
 * build a brand-new {@link OverAllState} per request (never the {@code invoke(OverAllState, ...)}
 * overload with a reused instance - see docs/spec.md §1.1). Wraps the invocation in a last-resort
 * try/catch so anything that slips past every node's own exception handling degrades gracefully
 * instead of surfacing as {@code GlobalExceptionHandler}'s generic 500.
 */
@Slf4j
@Service
public class ClaimsAgentOrchestratorService {

    private static final String UNABLE_TO_PROCESS_MESSAGE = "Unable to process this request.";

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

        OverAllState finalState;
        try {
            finalState = claimsAgentCompiledGraph.invoke(inputs)
                    .orElseThrow(() -> new IllegalStateException("Claims agent graph produced no final state, traceId=" + traceId));
        } catch (Exception e) {
            log.error("Claims agent graph invocation failed: traceId={}", traceId, e);
            return new AssistantQueryResponse(false, UNABLE_TO_PROCESS_MESSAGE, null);
        }

        AgentWorkflowState workflowState = new AgentWorkflowState(finalState);
        String finalAnswer = workflowState.finalAnswer();
        Boolean answered = workflowState.answered();

        if (answered != null) {
            log.info("Claims query completed via unified_react_agent: traceId={}, answered={}", traceId, answered);
            return new AssistantQueryResponse(answered, answered ? null : workflowState.failureReason(), finalAnswer);
        }

        log.info("Claims query answered via non-agent path: traceId={}", traceId);
        return new AssistantQueryResponse(true, null, finalAnswer);
    }
}
