package com.skyshift.cognitiveragengine.assistant.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.skyshift.cognitiveragengine.assistant.agent.AssistantReactAgentFactory;
import com.skyshift.cognitiveragengine.assistant.config.AssistantProperties;
import com.skyshift.cognitiveragengine.assistant.model.dto.AssistantResponse;
import com.skyshift.cognitiveragengine.assistant.model.enums.MessageRole;
import com.skyshift.cognitiveragengine.qa.model.SourceChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AssistantService {

    private static final String KNOWLEDGE_BASE_TOOL_NAME = "searchKnowledgeBase";

    private final AssistantReactAgentFactory assistantReactAgentFactory;
    private final ConversationService conversationService;
    private final ConversationSummaryService conversationSummaryService;
    private final AssistantProperties assistantProperties;

    public AssistantService(
            AssistantReactAgentFactory assistantReactAgentFactory,
            ConversationService conversationService,
            ConversationSummaryService conversationSummaryService,
            AssistantProperties assistantProperties
    ) {
        this.assistantReactAgentFactory = assistantReactAgentFactory;
        this.conversationService = conversationService;
        this.conversationSummaryService = conversationSummaryService;
        this.assistantProperties = assistantProperties;
    }

    public AssistantResponse ask(String message, Long groupId, Long conversationId) {
        log.info("Processing assistant message: groupId={}", groupId);

        Long resolvedConversationId = conversationService.getOrCreateConversation(conversationId, groupId);
        List<Document> retrievedDocuments = new CopyOnWriteArrayList<>();

        try {
            List<Message> fullMessages = new ArrayList<>(conversationService.loadHistory(
                    resolvedConversationId, assistantProperties.getMaxHistoryTurns()));
            fullMessages.add(new UserMessage(message));

            ReactAgent reactAgent = assistantReactAgentFactory.createAgent(groupId, retrievedDocuments);
            AssistantMessage assistantMessage = reactAgent.call(fullMessages);

            conversationService.appendMessage(resolvedConversationId, MessageRole.USER, message, null);
            if (!retrievedDocuments.isEmpty()) {
                conversationService.appendMessage(resolvedConversationId, MessageRole.TOOL,
                        "Searched knowledge base, retrieved %d chunk(s).".formatted(retrievedDocuments.size()),
                        KNOWLEDGE_BASE_TOOL_NAME);
            }
            conversationService.appendMessage(resolvedConversationId, MessageRole.ASSISTANT, assistantMessage.getText(), null);
            conversationSummaryService.maybeSummarize(resolvedConversationId);

            log.info("Assistant message answered successfully");

            return new AssistantResponse(true, "", convertToSourceChunks(retrievedDocuments), assistantMessage.getText(), resolvedConversationId);
        } catch (Exception e) {
            log.error("Error processing assistant message: groupId={}: {}", groupId, e.getMessage(), e);
            return new AssistantResponse(false, "Failed to process message: " + e.getMessage(), Collections.emptyList(), "", resolvedConversationId);
        }
    }

    private List<SourceChunk> convertToSourceChunks(List<Document> documents) {
        return documents.stream()
                .map(doc -> new SourceChunk(
                        doc.getText(),
                        Long.parseLong((String) doc.getMetadata().get("chunkId")),
                        Long.parseLong((String) doc.getMetadata().get("documentId")),
                        Integer.parseInt((String) doc.getMetadata().get("chunkNumber")),
                        Double.parseDouble((String) doc.getMetadata().get("similarity"))
                ))
                .collect(Collectors.toList());
    }
}