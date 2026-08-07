package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/** Generates an immediate, canned refusal for OUT_OF_SCOPE queries - no LLM call, saving tokens. */
@Slf4j
public class OutOfScopeNode implements NodeAction {

    public static final String NAME = "out_of_scope";

    private static final String REFUSAL_MESSAGE =
            "I'm only able to help with insurance policy and claims questions. Could you rephrase your question around one of those topics?";

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String originalQuery = state.value(WorkflowStateKeys.ORIGINAL_QUERY, "");
        log.info("out_of_scope refusing query: '{}'", originalQuery);

        return Map.of(WorkflowStateKeys.FINAL_ANSWER, REFUSAL_MESSAGE);
    }
}
