package com.skyshift.cognitiveragengine.assistant.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.skyshift.cognitiveragengine.assistant.config.AssistantProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Builds a fresh ReactAgent per call - no conversation memory yet, so nothing needs to survive
 * past the request, and a fresh instance guarantees the groupId-bound tool context below can
 * never bleed into another caller's request.
 */
@Component
public class AssistantReactAgentFactory {

    private final ChatModel chatModel;
    private final AssistantKnowledgeBaseTool assistantKnowledgeBaseTool;
    private final AssistantProperties assistantProperties;
    private final PromptTemplate assistantReactInstructionTemplate;
    private final ObservationRegistry observationRegistry;

    public AssistantReactAgentFactory(
            ChatModel chatModel,
            AssistantKnowledgeBaseTool assistantKnowledgeBaseTool,
            AssistantProperties assistantProperties,
            @Qualifier("assistantReactInstructionTemplate") PromptTemplate assistantReactInstructionTemplate,
            ObservationRegistry observationRegistry
    ) {
        this.chatModel = chatModel;
        this.assistantKnowledgeBaseTool = assistantKnowledgeBaseTool;
        this.assistantProperties = assistantProperties;
        this.assistantReactInstructionTemplate = assistantReactInstructionTemplate;
        this.observationRegistry = observationRegistry;
    }

    public ReactAgent createAgent(Long groupId, List<Document> retrievedDocuments) {
        return ReactAgent.builder()
                .name("assistant-react-agent")
                .model(chatModel)
                .instruction(assistantReactInstructionTemplate.getTemplate())
                .methodTools(assistantKnowledgeBaseTool)
                .toolContext(Map.of(
                        AssistantKnowledgeBaseTool.GROUP_ID_CONTEXT_KEY, groupId,
                        AssistantKnowledgeBaseTool.RESULT_HOLDER_CONTEXT_KEY, retrievedDocuments
                ))
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(assistantProperties.getMaxToolLoops())
                        .build())
                .toolExecutionTimeout(Duration.ofMillis(assistantProperties.getToolTimeoutMs()))
                .observationRegistry(observationRegistry)
                .build();
    }
}