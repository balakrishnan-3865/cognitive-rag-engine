package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Structured LLM output for {@link QueryPlannerNode}, parsed via {@code ChatClient.entity(...)}. */
record QueryPlanResponse(
        @JsonProperty("subqueries") List<String> subqueries
) {
}
