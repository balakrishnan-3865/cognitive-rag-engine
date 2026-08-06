package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.skyshift.cognitiveragengine.tools.ClaimStatusTool;
import com.skyshift.cognitiveragengine.tools.ContextKeys;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles a single, deterministic claims-status lookup. Bypasses {@code ReactAgent} entirely -
 * {@link ClaimStatusTool} exposes exactly one method and needs no multi-tool selection - but binds
 * it directly on the {@link ChatClient} call so ordinary Spring AI tool-calling extracts any date
 * range mentioned in the query, invokes the tool, and phrases the answer, all in one {@code call()}
 * (docs/spec.md §3.2).
 */
@Slf4j
public class ClaimStatusDirectNode implements NodeAction {

    public static final String NAME = "claim_status_direct";

    private final ChatClient chatClient;
    private final ClaimStatusTool claimStatusTool;

    public ClaimStatusDirectNode(ChatClient chatClient, ClaimStatusTool claimStatusTool) {
        this.chatClient = chatClient;
        this.claimStatusTool = claimStatusTool;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String originalQuery = state.value(WorkflowStateKeys.ORIGINAL_QUERY, "");
        Long groupId = state.value(WorkflowStateKeys.GROUP_ID, (Long) null);
        Long userId = state.value(WorkflowStateKeys.USER_ID, (Long) null);

        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ContextKeys.GROUP_ID_CONTEXT_KEY, groupId);
        toolContext.put(ContextKeys.USER_ID_CONTEXT_KEY, userId);

        String phrased = chatClient.prompt(originalQuery)
                .tools(claimStatusTool)
                .toolContext(toolContext)
                .call()
                .content();

        log.info("claim_status_direct produced answer for groupId={}", groupId);

        return Map.of(WorkflowStateKeys.FINAL_ANSWER, phrased);
    }
}
