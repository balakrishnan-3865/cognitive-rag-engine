package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.skyshift.cognitiveragengine.workflows.claims.state.AgentWorkflowState;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/** Returns an immediate, canned greeting for GENERAL_GREETING queries - no LLM call, saving tokens. */
@Slf4j
public class DirectChatNode implements NodeAction {

    public static final String NAME = "direct_chat";

    private static final String GREETING_MESSAGE =
            "Hello! I'm here to help with your insurance policy and claims questions. What can I help you with?";

    @Override
    public Map<String, Object> apply(OverAllState state) {
        AgentWorkflowState workflowState = new AgentWorkflowState(state);
        log.info("direct_chat responding to greeting: '{}'", workflowState.originalQuery());

        return Map.of(WorkflowStateKeys.FINAL_ANSWER, GREETING_MESSAGE);
    }
}
