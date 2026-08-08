package com.skyshift.cognitiveragengine.assistant.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
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
        when(agent.call(anyList())).thenThrow(new RuntimeException("Tool 'unknownTool' not found"));

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
}
