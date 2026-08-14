package com.skyshift.cognitiveragengine.assistant.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level coverage of {@link AwarenessHook#beforeModel} in isolation from the graph -
 * state/config are unused by this hook (it only tracks its own call count), so mocks are
 * never stubbed, just passed through.
 */
@ExtendWith(MockitoExtension.class)
class AwarenessHookTest {

    @Mock
    private OverAllState state;
    @Mock
    private RunnableConfig config;

    @Test
    void belowThreshold_beforeModel_injectsNoMessage() throws ExecutionException, InterruptedException {
        AwarenessHook hook = new AwarenessHook(3);

        Map<String, Object> firstCall = hook.beforeModel(state, config).get();
        Map<String, Object> secondCall = hook.beforeModel(state, config).get();

        assertTrue(firstCall.isEmpty());
        assertTrue(secondCall.isEmpty());
    }

    @Test
    void atThreshold_beforeModel_injectsWarningMessage() throws ExecutionException, InterruptedException {
        AwarenessHook hook = new AwarenessHook(3);
        hook.beforeModel(state, config).get();
        hook.beforeModel(state, config).get();

        Map<String, Object> thirdCall = hook.beforeModel(state, config).get();

        assertTrue(thirdCall.containsKey("messages"));
        @SuppressWarnings("unchecked")
        List<Message> injected = (List<Message>) thirdCall.get("messages");
        assertEquals(1, injected.size());
        assertTrue(injected.get(0).getText().contains(AwarenessHook.WARNING_MESSAGE));
    }

    @Test
    void pastThreshold_beforeModel_keepsInjectingWarningMessage() throws ExecutionException, InterruptedException {
        AwarenessHook hook = new AwarenessHook(3);
        hook.beforeModel(state, config).get();
        hook.beforeModel(state, config).get();
        hook.beforeModel(state, config).get();

        Map<String, Object> fourthCall = hook.beforeModel(state, config).get();

        assertFalse(fourthCall.isEmpty());
        assertTrue(fourthCall.containsKey("messages"));
    }
}
