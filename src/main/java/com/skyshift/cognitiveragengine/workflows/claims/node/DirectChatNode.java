package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

/** Bypasses the planning pipeline for GENERAL_GREETING, generating a direct conversational response. */
@Slf4j
public class DirectChatNode implements NodeAction {

    public static final String NAME = "direct_chat";

    private static final String SYSTEM_PROMPT = """
            You are a friendly assistant for an insurance customer service system. Respond briefly
            and warmly to greetings and small talk. Do not attempt to answer policy or claims
            questions here - if the customer asks one, gently redirect them to ask it directly.
            """;

    private final ChatClient chatClient;

    public DirectChatNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String originalQuery = state.value(WorkflowStateKeys.ORIGINAL_QUERY, "");

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(originalQuery)
                .call()
                .content();

        log.info("direct_chat produced response");

        return Map.of(WorkflowStateKeys.FINAL_ANSWER, response);
    }
}
