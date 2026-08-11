package com.skyshift.cognitiveragengine.workflows.claims.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.skyshift.cognitiveragengine.document.service.DocumentService;
import com.skyshift.cognitiveragengine.qa.model.SourceChunk;
import com.skyshift.cognitiveragengine.workflows.claims.model.dto.AssistantQueryResponse;
import com.skyshift.cognitiveragengine.workflows.claims.state.AgentWorkflowState;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
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
    private final DocumentService documentService;

    public ClaimsAgentOrchestratorService(CompiledGraph claimsAgentCompiledGraph, DocumentService documentService) {
        this.claimsAgentCompiledGraph = claimsAgentCompiledGraph;
        this.documentService = documentService;
    }

    public AssistantQueryResponse query(String query, Long groupId, Long userId, Long documentId) {
        String traceId = UUID.randomUUID().toString();
        log.info("Processing claims query: traceId={}, groupId={}, documentId={}", traceId, groupId, documentId);

        // Invalid/inaccessible documentId is a clear rejection (BusinessException -> 400 via
        // GlobalExceptionHandler) - validated before the graph invocation so it can never be
        // folded into the graceful-degradation response below.
        documentService.resolveSearchableDocumentIds(groupId, documentId);

        Map<String, Object> inputs = new HashMap<>();
        inputs.put(WorkflowStateKeys.ORIGINAL_QUERY, query);
        inputs.put(WorkflowStateKeys.GROUP_ID, groupId);
        inputs.put(WorkflowStateKeys.USER_ID, userId);
        if (documentId != null) {
            inputs.put(WorkflowStateKeys.DOCUMENT_ID, documentId);
        }

        OverAllState finalState;
        try {
            finalState = claimsAgentCompiledGraph.invoke(inputs)
                    .orElseThrow(() -> new IllegalStateException("Claims agent graph produced no final state, traceId=" + traceId));
        } catch (Exception e) {
            log.error("Claims agent graph invocation failed: traceId={}", traceId, e);
            return new AssistantQueryResponse(false, UNABLE_TO_PROCESS_MESSAGE, List.of(), null);
        }

        AgentWorkflowState workflowState = new AgentWorkflowState(finalState);
        String finalAnswer = workflowState.finalAnswer();
        Boolean answered = workflowState.answered();
        List<SourceChunk> sources = workflowState.sources() != null ? workflowState.sources() : List.of();

        if (answered != null) {
            log.info("Claims query completed via unified_react_agent: traceId={}, answered={}", traceId, answered);
            return new AssistantQueryResponse(answered, answered ? null : workflowState.failureReason(), sources, finalAnswer);
        }

        log.info("Claims query answered via non-agent path: traceId={}", traceId);
        return new AssistantQueryResponse(true, null, sources, finalAnswer);
    }
}
