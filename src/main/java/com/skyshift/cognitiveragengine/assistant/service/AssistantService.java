package com.skyshift.cognitiveragengine.assistant.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.skyshift.cognitiveragengine.assistant.agent.AssistantReactAgentFactory;
import com.skyshift.cognitiveragengine.assistant.model.dto.AssistantResponse;
import com.skyshift.cognitiveragengine.qa.model.SourceChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AssistantService {

    private final AssistantReactAgentFactory assistantReactAgentFactory;

    public AssistantService(AssistantReactAgentFactory assistantReactAgentFactory) {
        this.assistantReactAgentFactory = assistantReactAgentFactory;
    }

    public AssistantResponse ask(String message, Long groupId) {
        log.info("Processing assistant message: groupId={}", groupId);

        List<Document> retrievedDocuments = new CopyOnWriteArrayList<>();

        try {
            ReactAgent reactAgent = assistantReactAgentFactory.createAgent(groupId, retrievedDocuments);
            AssistantMessage assistantMessage = reactAgent.call(message);

            log.info("Assistant message answered successfully");

            return new AssistantResponse(true, "", convertToSourceChunks(retrievedDocuments), assistantMessage.getText());
        } catch (Exception e) {
            log.error("Error processing assistant message: groupId={}: {}", groupId, e.getMessage(), e);
            return new AssistantResponse(false, "Failed to process message: " + e.getMessage(), Collections.emptyList(), "");
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