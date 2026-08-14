package com.skyshift.cognitiveragengine.assistant.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitExceededException;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.skyshift.cognitiveragengine.assistant.config.AssistantProperties;
import com.skyshift.cognitiveragengine.common.exception.MalformedToolCallException;
import com.skyshift.cognitiveragengine.common.exception.RecursionLimitExceededException;
import com.skyshift.cognitiveragengine.common.exception.ToolExecutionTimeoutException;
import com.skyshift.cognitiveragengine.common.exception.UncategorizedAgentException;
import com.skyshift.cognitiveragengine.tools.ClaimStatusTool;
import com.skyshift.cognitiveragengine.tools.KnowledgeBaseTool;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Covers {@link AssistantReactAgentFactory#categorizeAndThrowException}, including the new
 * default branch (docs/spec.md, "AssistantReactAgentFactory.java (bug fix)") that closes the
 * fallthrough gap: an exception matching none of the 4 known shapes must now surface as
 * {@link UncategorizedAgentException}, not a bare {@link RuntimeException}.
 */
@ExtendWith(MockitoExtension.class)
class AssistantReactAgentFactoryTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private KnowledgeBaseTool knowledgeBaseTool;
    @Mock
    private ClaimStatusTool claimStatusTool;
    @Mock
    private AssistantProperties assistantProperties;
    @Mock
    private ObservationRegistry observationRegistry;
    @Mock
    private ReactAgent agent;

    private final PromptTemplate instructionTemplate = new PromptTemplate("You are a helpful assistant.");
    private final List<Message> messages = List.of(new UserMessage("What's the status of my claim?"));

    private AssistantReactAgentFactory factory;

    @BeforeEach
    void setUp() {
        factory = new AssistantReactAgentFactory(
                chatModel, knowledgeBaseTool, claimStatusTool, assistantProperties, instructionTemplate, observationRegistry);
    }

    @Test
    void toolNotFoundMessage_throwsMalformedToolCallException() throws GraphRunnerException {
        when(agent.call(anyList())).thenThrow(
                new IllegalStateException("No ToolCallback found for tool name: unknownTool"));

        assertThrows(MalformedToolCallException.class, () -> factory.callWithErrorHandling(agent, messages));
    }

    @Test
    void malformedJsonMessage_throwsMalformedToolCallException() throws GraphRunnerException {
        when(agent.call(anyList())).thenThrow(new RuntimeException("Failed to parse tool call JSON"));

        assertThrows(MalformedToolCallException.class, () -> factory.callWithErrorHandling(agent, messages));
    }

    @Test
    void recursionLimitMessage_throwsRecursionLimitExceededException() throws GraphRunnerException {
        when(agent.call(anyList())).thenThrow(new RuntimeException("recursion limit exceeded"));

        assertThrows(RecursionLimitExceededException.class, () -> factory.callWithErrorHandling(agent, messages));
    }

    @Test
    void timeoutMessage_throwsToolExecutionTimeoutException() throws GraphRunnerException {
        when(agent.call(anyList())).thenThrow(new RuntimeException("tool call timeout after 30s"));

        assertThrows(ToolExecutionTimeoutException.class, () -> factory.callWithErrorHandling(agent, messages));
    }

    @Test
    void uncategorizedFailure_throwsUncategorizedAgentExceptionInsteadOfBareRuntimeException() throws GraphRunnerException {
        RuntimeException original = new RuntimeException("database connection reset");
        when(agent.call(anyList())).thenThrow(original);

        UncategorizedAgentException thrown = assertThrows(UncategorizedAgentException.class,
                () -> factory.callWithErrorHandling(agent, messages));

        assertEquals("database connection reset", thrown.getMessage());
        assertEquals(original, thrown.getCause());
    }

    @Test
    void runawayToolLoop_throwsModelCallLimitExceededWithinMaxModelCallsCalls() {
        when(assistantProperties.getToolTimeoutMs()).thenReturn(5000L);
        when(assistantProperties.getMaxModelCalls()).thenReturn(3);
        when(knowledgeBaseTool.searchKnowledgeBase(anyString(), any())).thenReturn("dummy result");

        AtomicInteger modelCallCount = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            int n = modelCallCount.incrementAndGet();
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    "call-" + n, "function", "searchKnowledgeBase", "{\"query\":\"claim status\"}");
            AssistantMessage toolCallMessage = AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
            return new ChatResponse(List.of(new Generation(toolCallMessage)));
        });

        // ObservationRegistry.NOOP instead of the shared @Mock: the mocked registry NPEs deep
        // inside the framework's observation wrapping (observationConfig() returns null on an
        // unstubbed mock), which the framework swallows into the response text rather than
        // propagating - masking the very behavior this test verifies.
        AssistantReactAgentFactory realObservabilityFactory = new AssistantReactAgentFactory(
                chatModel, knowledgeBaseTool, claimStatusTool, assistantProperties, instructionTemplate,
                ObservationRegistry.NOOP);
        ReactAgent realAgent = realObservabilityFactory.createAgent(1L, 2L, null, new ArrayList<>());

        assertThrows(ModelCallLimitExceededException.class, () -> realAgent.call(messages));
        assertEquals(3, modelCallCount.get());
    }

    @Test
    void modelCallLimitExceededException_throwsRecursionLimitExceededException() throws GraphRunnerException {
        when(agent.call(anyList())).thenThrow(new ModelCallLimitExceededException(3, 3, null, 3));

        assertThrows(RecursionLimitExceededException.class, () -> factory.callWithErrorHandling(agent, messages));
    }

    @Test
    void repeatedSameToolCalls_throwsToolCallLimitExceededBeforeModelCallLimit() {
        when(assistantProperties.getToolTimeoutMs()).thenReturn(5000L);
        // maxModelCalls set high on purpose: the tool-specific limit (3 calls to
        // searchKnowledgeBase) must trip well before the aggregate model-call budget would,
        // proving this guards a distinct failure mode from Phase 2 (same tool, reworded
        // queries, never converging, while staying within the LLM-call budget).
        when(assistantProperties.getMaxModelCalls()).thenReturn(10);
        when(knowledgeBaseTool.searchKnowledgeBase(anyString(), any())).thenReturn("dummy result");

        AtomicInteger modelCallCount = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            int n = modelCallCount.incrementAndGet();
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    "call-" + n, "function", "searchKnowledgeBase", "{\"query\":\"claim status attempt " + n + "\"}");
            AssistantMessage toolCallMessage = AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
            return new ChatResponse(List.of(new Generation(toolCallMessage)));
        });

        AssistantReactAgentFactory realObservabilityFactory = new AssistantReactAgentFactory(
                chatModel, knowledgeBaseTool, claimStatusTool, assistantProperties, instructionTemplate,
                ObservationRegistry.NOOP);
        ReactAgent realAgent = realObservabilityFactory.createAgent(1L, 2L, null, new ArrayList<>());

        ToolCallLimitExceededException thrown = assertThrows(ToolCallLimitExceededException.class,
                () -> realAgent.call(messages));
        assertEquals(3, thrown.getRunCount());
        assertEquals(3, modelCallCount.get());
    }

    @Test
    void toolCallLimitExceededException_throwsRecursionLimitExceededException() throws GraphRunnerException {
        when(agent.call(anyList())).thenThrow(
                new ToolCallLimitExceededException(0, 3, null, 3, "searchKnowledgeBase"));

        assertThrows(RecursionLimitExceededException.class, () -> factory.callWithErrorHandling(agent, messages));
    }

    @Test
    void nearModelCallLimit_awarenessHookNudgesModelToConvergeEarly() throws GraphRunnerException {
        when(assistantProperties.getToolTimeoutMs()).thenReturn(5000L);
        // maxModelCalls=5 -> warningThreshold = maxModelCalls - 2 = 3: the nudge should appear
        // in the prompt sent on the 3rd model call, before ModelCallLimitHook would ever trip.
        when(assistantProperties.getMaxModelCalls()).thenReturn(5);
        when(knowledgeBaseTool.searchKnowledgeBase(anyString(), any())).thenReturn("dummy result");

        AtomicInteger modelCallCount = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            int n = modelCallCount.incrementAndGet();
            Prompt prompt = invocation.getArgument(0);
            boolean warned = prompt.getInstructions().stream()
                    .anyMatch(m -> m.getText() != null && m.getText().contains(AwarenessHook.WARNING_MESSAGE));

            if (warned) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Here is your claim status."))));
            }

            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    "call-" + n, "function", "searchKnowledgeBase", "{\"query\":\"claim status attempt " + n + "\"}");
            AssistantMessage toolCallMessage = AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
            return new ChatResponse(List.of(new Generation(toolCallMessage)));
        });

        AssistantReactAgentFactory realObservabilityFactory = new AssistantReactAgentFactory(
                chatModel, knowledgeBaseTool, claimStatusTool, assistantProperties, instructionTemplate,
                ObservationRegistry.NOOP);
        ReactAgent realAgent = realObservabilityFactory.createAgent(1L, 2L, null, new ArrayList<>());

        AssistantMessage result = realAgent.call(messages);

        assertEquals("Here is your claim status.", result.getText());
        assertEquals(3, modelCallCount.get());
    }
}
