package com.skyshift.cognitiveragengine.workflows.claims.state;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Outcome of a single subquery pass through {@code subquery_loop_executor}. A single Append-strategy
 * list of these (keyed by {@link WorkflowStateKeys#SUBQUERY_RESULTS}) replaces two parallel
 * accumulator lists so a pass contributing zero source documents can never desynchronize the two.
 */
public record SubqueryResult(
        String subquery,
        String answerText,
        List<Document> sourceDocuments,
        boolean failed,
        String failureReason
) {
}
