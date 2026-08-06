package com.skyshift.cognitiveragengine.workflows.claims.state;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Grounding verdict produced by {@code reflection_check}, also used as the structured target type
 * for the node's ChatClient {@code .entity(ReflectionResult.class)} call. Only populated on the
 * query_planner -> subquery_loop_executor -> reflection_check path; drives
 * {@link com.skyshift.cognitiveragengine.workflows.claims.model.dto.AssistantQueryResponse}'s
 * answered/reasonMessage fields (docs/spec.md §1.4, §5).
 */
public record ReflectionResult(
        @JsonProperty("grounded") boolean grounded,
        @JsonProperty("reason") String reason
) {
}
