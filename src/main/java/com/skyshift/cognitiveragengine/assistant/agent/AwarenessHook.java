package com.skyshift.cognitiveragengine.assistant.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Nudges the model to wrap up once it is within {@code warningThreshold} model calls of the
 * hard {@link com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook} budget,
 * so a run that would otherwise be cut off mid-reasoning gets a chance to converge on a real
 * answer before the hard stop.
 *
 * callCount is a plain field, not an AtomicInteger: AssistantReactAgentFactory builds a fresh
 * ReactAgent (and therefore a fresh AwarenessHook instance) per request, and a single request's
 * graph execution runs its beforeModel/model/afterModel/tool nodes sequentially - no two threads
 * ever observe or mutate the same instance's callCount.
 */
@HookPositions({HookPosition.BEFORE_MODEL})
public class AwarenessHook extends ModelHook {

    static final String WARNING_MESSAGE =
            "You are approaching the reasoning limit for this request. Provide your final answer now instead of calling another tool.";

    private final int warningThreshold;
    private int callCount = 0;

    public AwarenessHook(int warningThreshold) {
        this.warningThreshold = warningThreshold;
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        callCount++;
        if (callCount >= warningThreshold) {
            List<Message> messages = List.of(new UserMessage(WARNING_MESSAGE));
            return CompletableFuture.completedFuture(Map.of("messages", messages));
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public String getName() {
        return "AwarenessHook";
    }
}
