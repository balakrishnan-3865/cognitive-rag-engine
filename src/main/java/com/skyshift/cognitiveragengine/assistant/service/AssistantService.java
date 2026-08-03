package com.skyshift.cognitiveragengine.assistant.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.skyshift.cognitiveragengine.assistant.agent.AssistantReactAgentFactory;
import com.skyshift.cognitiveragengine.assistant.config.AssistantProperties;
import com.skyshift.cognitiveragengine.assistant.model.dto.AssistantResponse;
import com.skyshift.cognitiveragengine.assistant.model.enums.MessageRole;
import com.skyshift.cognitiveragengine.common.converter.DocumentToSourceChunkConverter;
import com.skyshift.cognitiveragengine.common.exception.MalformedToolCallException;
import com.skyshift.cognitiveragengine.common.exception.RecursionLimitExceededException;
import com.skyshift.cognitiveragengine.common.exception.ToolExecutionTimeoutException;
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
            AssistantMessage assistantMessage = assistantReactAgentFactory.callWithErrorHandling(reactAgent, new ArrayList<>(fullMessages));

            conversationService.appendMessage(resolvedConversationId, MessageRole.USER, message, null);
            if (!retrievedDocuments.isEmpty()) {
                conversationService.appendMessage(resolvedConversationId, MessageRole.TOOL,
                        "Searched knowledge base, retrieved %d chunk(s).".formatted(retrievedDocuments.size()),
                        KNOWLEDGE_BASE_TOOL_NAME);
            }
            conversationService.appendMessage(resolvedConversationId, MessageRole.ASSISTANT, assistantMessage.getText(), null);
            conversationSummaryService.maybeSummarize(resolvedConversationId);

            log.info("Assistant message answered successfully");

            return new AssistantResponse(true, "", DocumentToSourceChunkConverter.convertAll(retrievedDocuments), assistantMessage.getText(), resolvedConversationId);

        } catch (MalformedToolCallException e) {
            log.warn("Malformed tool call detected (recoverable): groupId={}, error={}", groupId, e.getMessage());
            return attemptRepairLoop(message, groupId, resolvedConversationId, retrievedDocuments, e);

        } catch (RecursionLimitExceededException e) {
            log.error("Recursion limit exceeded: groupId={}", groupId);
            return new AssistantResponse(false,
                    "Request exceeded maximum reasoning depth. Please ask a more focused question.",
                    Collections.emptyList(), "", resolvedConversationId);

        } catch (ToolExecutionTimeoutException e) {
            log.warn("Tool execution timeout (transient): groupId={}", groupId);
            return new AssistantResponse(false,
                    "Knowledge base search timed out. Please try again.",
                    Collections.emptyList(), "", resolvedConversationId);

        } catch (Exception e) {
            log.error("Unexpected error processing assistant message: groupId={}", groupId, e);
            return new AssistantResponse(false,
                    "An unexpected error occurred. Please try again.",
                    Collections.emptyList(), "", resolvedConversationId);
        }
    }

    private AssistantResponse attemptRepairLoop(
            String originalMessage,
            Long groupId,
            Long conversationId,
            List<Document> retrievedDocuments,
            MalformedToolCallException initialError) {

        log.info("Initiating repair loop for malformed tool call: groupId={}", groupId);

        try {
            List<Message> fullMessages = new ArrayList<>(conversationService.loadHistory(
                    conversationId, assistantProperties.getMaxHistoryTurns()));
            fullMessages.add(new UserMessage(originalMessage));

            String repairInstruction = buildRepairInstruction(initialError);
            fullMessages.add(new UserMessage(repairInstruction));

            ReactAgent reactAgent = assistantReactAgentFactory.createAgent(groupId, retrievedDocuments);
            AssistantMessage assistantMessage = reactAgent.call(fullMessages);

            log.info("Repair loop succeeded on retry: groupId={}", groupId);

            conversationService.appendMessage(conversationId, MessageRole.USER, originalMessage, null);
            conversationService.appendMessage(conversationId, MessageRole.USER, repairInstruction, null);
            if (!retrievedDocuments.isEmpty()) {
                conversationService.appendMessage(conversationId, MessageRole.TOOL,
                        "Searched knowledge base, retrieved %d chunk(s).".formatted(retrievedDocuments.size()),
                        KNOWLEDGE_BASE_TOOL_NAME);
            }
            conversationService.appendMessage(conversationId, MessageRole.ASSISTANT, assistantMessage.getText(), null);
            conversationSummaryService.maybeSummarize(conversationId);

            return new AssistantResponse(true, "",
                    DocumentToSourceChunkConverter.convertAll(retrievedDocuments),
                    assistantMessage.getText(), conversationId);

        } catch (Exception retryError) {
            log.error("Repair loop failed: groupId={}, error={}", groupId, retryError.getMessage(), retryError);
            return new AssistantResponse(false,
                    "Could not process your request after attempting to correct the reasoning. Please try a simpler question.",
                    Collections.emptyList(), "", conversationId);
        }
    }

    private String buildRepairInstruction(MalformedToolCallException error) {
        String toolName = extractHallucinatedToolName(error);
        List<String> validTools = List.of("searchKnowledgeBase");

        return String.format(
                "Your last tool call was invalid: '%s' does not exist. " +
                "Available tools: %s. " +
                "Retry your reasoning with a valid tool call.",
                toolName, validTools);
    }

    private String extractHallucinatedToolName(MalformedToolCallException error) {
        String message = error.getMessage();
        if (message == null) {
            return "unknown";
        }

        if (message.contains("'")) {
            int start = message.indexOf("'") + 1;
            int end = message.lastIndexOf("'");
            if (start > 0 && end > start) {
                return message.substring(start, end);
            }
        }
        return "unknown";
    }

}