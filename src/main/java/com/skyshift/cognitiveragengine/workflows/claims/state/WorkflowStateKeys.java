package com.skyshift.cognitiveragengine.workflows.claims.state;

/**
 * String keys used to read and write the claims agent graph's {@link com.alibaba.cloud.ai.graph.OverAllState}.
 * See docs/spec.md §1.2 for the key strategy table each of these is bound to.
 */
public final class WorkflowStateKeys {

    public static final String ORIGINAL_QUERY = "originalQuery";
    public static final String GROUP_ID = "groupId";
    public static final String USER_ID = "userId";
    public static final String ROUTING_INTENT = "routingIntent";
    public static final String FINAL_ANSWER = "finalAnswer";
    public static final String ANSWERED = "answered";
    public static final String FAILURE_REASON = "failureReason";

    private WorkflowStateKeys() {
    }
}
