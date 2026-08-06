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
    public static final String SUBQUERIES = "subqueries";
    public static final String CURRENT_SUBQUERY_INDEX = "currentSubqueryIndex";
    public static final String SUBQUERY_RESULTS = "subqueryResults";
    public static final String REFLECTION_RESULT = "reflectionResult";
    public static final String FINAL_ANSWER = "finalAnswer";

    private WorkflowStateKeys() {
    }
}
