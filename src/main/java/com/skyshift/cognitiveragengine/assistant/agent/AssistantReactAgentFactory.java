package com.skyshift.cognitiveragengine.assistant.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitExceededException;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.skyshift.cognitiveragengine.assistant.config.AssistantProperties;
import com.skyshift.cognitiveragengine.common.exception.MalformedToolCallException;
import com.skyshift.cognitiveragengine.common.exception.RecursionLimitExceededException;
import com.skyshift.cognitiveragengine.common.exception.ToolExecutionTimeoutException;
import com.skyshift.cognitiveragengine.common.exception.UncategorizedAgentException;
import com.skyshift.cognitiveragengine.tools.ClaimStatusTool;
import com.skyshift.cognitiveragengine.tools.ContextKeys;
import com.skyshift.cognitiveragengine.tools.KnowledgeBaseTool;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Builds a fresh ReactAgent per call - no conversation memory yet, so nothing needs to survive
 * past the request, and a fresh instance guarantees the groupId-bound tool context below can
 * never bleed into another caller's request.
 */
@Slf4j
@Component
public class AssistantReactAgentFactory {

    private final ChatModel chatModel;
    private final KnowledgeBaseTool knowledgeBaseTool;
    private final ClaimStatusTool claimStatusTool;
    private final AssistantProperties assistantProperties;
    private final PromptTemplate assistantReactInstructionTemplate;
    private final ObservationRegistry observationRegistry;
    private final List<String> registeredToolNames;

    public AssistantReactAgentFactory(
            @Qualifier("assistantChatModel") ChatModel chatModel,
            KnowledgeBaseTool knowledgeBaseTool,
            ClaimStatusTool claimStatusTool,
            AssistantProperties assistantProperties,
            @Qualifier("assistantReactInstructionTemplate") PromptTemplate assistantReactInstructionTemplate,
            ObservationRegistry observationRegistry
    ) {
        this.chatModel = chatModel;
        this.knowledgeBaseTool = knowledgeBaseTool;
        this.claimStatusTool = claimStatusTool;
        this.assistantProperties = assistantProperties;
        this.assistantReactInstructionTemplate = assistantReactInstructionTemplate;
        this.observationRegistry = observationRegistry;
        this.registeredToolNames = Arrays.stream(ToolCallbacks.from(knowledgeBaseTool, claimStatusTool))
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    public List<String> registeredToolNames() {
        return registeredToolNames;
    }

    public ReactAgent createAgent(Long groupId, Long userId, Long documentId, List<Document> retrievedDocuments) {
        Map<String, Object> context = new HashMap<>();
        context.put(ContextKeys.GROUP_ID_CONTEXT_KEY, groupId);
        context.put(ContextKeys.USER_ID_CONTEXT_KEY, userId);
        if (documentId != null) {
            context.put(ContextKeys.DOCUMENT_ID_CONTEXT_KEY, documentId);
        }
        context.put(ContextKeys.RESULT_HOLDER_CONTEXT_KEY, retrievedDocuments);

        return ReactAgent.builder()
                .name("assistant-react-agent")
                .model(chatModel)
                .instruction(assistantReactInstructionTemplate.getTemplate())
                .methodTools(knowledgeBaseTool, claimStatusTool)
                .toolContext(context)
                .hooks(new AwarenessHook(assistantProperties.getMaxModelCalls() - 2),
                        ModelCallLimitHook.builder()
                                .runLimit(assistantProperties.getMaxModelCalls())
                                .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
                                .build(),
                        ToolCallLimitHook.builder()
                                .toolName("searchKnowledgeBase")
                                .runLimit(3)
                                .exitBehavior(ToolCallLimitHook.ExitBehavior.ERROR)
                                .build())
                .compileConfig(CompileConfig.builder()
                        // Each round costs 7 graph steps: AwarenessHook only registers a
                        // beforeModel node (@HookPositions restricts it to BEFORE_MODEL, so it
                        // doesn't add an afterModel step), plus ModelCallLimitHook and
                        // ToolCallLimitHook's beforeModel+afterModel pairs (2 each) - beforeModel
                        // x3, model, afterModel x2, tool. This must clear 7 * maxModelCalls with
                        // margin, or the structural recursion limit trips first and gets silently
                        // swallowed by ReactAgent.call() (returns a truncated answer instead of
                        // throwing) before any hook ever fires.
                        .recursionLimit(assistantProperties.getMaxModelCalls() * 8 + 5)
                        .build())
                .toolExecutionTimeout(Duration.ofMillis(assistantProperties.getToolTimeoutMs()))
                .observationRegistry(observationRegistry)
                .build();
    }

    public AssistantMessage callWithErrorHandling(ReactAgent agent, List<Message> messages) {
        try {
            return agent.call(messages);
        } catch (Exception e) {
            throw categorizeException(e);
        }
    }

    private RuntimeException categorizeException(Exception e) {
        if (isToolNotFound(e) || isMalformedJson(e)) {
            return new MalformedToolCallException(e.getMessage(), e);
        }
        if (e instanceof ModelCallLimitExceededException) {
            return new RecursionLimitExceededException(e.getMessage());
        }
        if (e instanceof ToolCallLimitExceededException) {
            return new RecursionLimitExceededException(e.getMessage());
        }
        if (isRecursionLimitExceeded(e)) {
            return new RecursionLimitExceededException(e.getMessage());
        }
        if (isToolTimeout(e)) {
            return new ToolExecutionTimeoutException(e.getMessage());
        }
        return new UncategorizedAgentException(e.getMessage(), e);
    }

    private static boolean isToolNotFound(Exception e) {
        String message = e.getMessage();
        return message != null && message.contains("No ToolCallback found for tool name");
    }

    private static boolean isMalformedJson(Exception e) {
        return e.getCause() instanceof com.fasterxml.jackson.core.JsonParseException ||
               (e.getMessage() != null && (e.getMessage().contains("JSON") ||
                                          e.getMessage().contains("parse")));
    }

    private static boolean isRecursionLimitExceeded(Exception e) {
        String message = e.getMessage();
        return message != null && (message.contains("recursion") ||
                                   message.contains("limit exceeded") ||
                                   message.contains("max iterations"));
    }

    private static boolean isToolTimeout(Exception e) {
        return e instanceof TimeoutException ||
               (e.getMessage() != null && e.getMessage().contains("timeout"));
    }
}