package com.skyshift.cognitiveragengine.workflows.claims.state;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;
import com.skyshift.cognitiveragengine.qa.model.SourceChunk;

import java.util.List;

/**
 * Thin, per-request read layer over the graph's {@link OverAllState}. {@code OverAllState} is
 * {@code final} and its own javadoc states it is not thread-safe, so this wraps a delegate via
 * composition rather than extending it (docs/spec.md §1.1). Nodes may use this as a convenience;
 * they must still implement {@code NodeAction} against the raw {@code OverAllState} and return the
 * {@code Map<String, Object>} the graph engine requires - this class is not a substitute for that.
 */
public class AgentWorkflowState {

    private final OverAllState delegate;

    public AgentWorkflowState(OverAllState delegate) {
        this.delegate = delegate;
    }

    public String originalQuery() {
        return delegate.value(WorkflowStateKeys.ORIGINAL_QUERY, "");
    }

    public Long groupId() {
        return delegate.value(WorkflowStateKeys.GROUP_ID, (Long) null);
    }

    public Long userId() {
        return delegate.value(WorkflowStateKeys.USER_ID, (Long) null);
    }

    public Long documentId() {
        return delegate.value(WorkflowStateKeys.DOCUMENT_ID, (Long) null);
    }

    public RoutingIntent routingIntent() {
        return delegate.value(WorkflowStateKeys.ROUTING_INTENT, (RoutingIntent) null);
    }

    public String finalAnswer() {
        return delegate.value(WorkflowStateKeys.FINAL_ANSWER, "");
    }

    public Boolean answered() {
        return delegate.value(WorkflowStateKeys.ANSWERED, (Boolean) null);
    }

    public String failureReason() {
        return delegate.value(WorkflowStateKeys.FAILURE_REASON, (String) null);
    }

    public List<SourceChunk> sources() {
        return delegate.value(WorkflowStateKeys.SOURCES, (List<SourceChunk>) null);
    }
}
