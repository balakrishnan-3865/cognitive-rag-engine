package com.skyshift.cognitiveragengine.qa.service;

import com.skyshift.cognitiveragengine.common.converter.DocumentToSourceChunkConverter;
import com.skyshift.cognitiveragengine.qa.config.QaProperties;
import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import com.skyshift.cognitiveragengine.qa.model.KnowledgeSourceResponse;
import com.skyshift.cognitiveragengine.qa.model.SourceChunk;
import com.skyshift.cognitiveragengine.qa.model.dto.QAResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.client.ChatClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
public class QaService {

    private final ReadyChunkDocumentRetriever readyChunkDocumentRetriever;
    private final ChatClient chatClient;
    private final PromptTemplate qaQueryPromptTemplate;
    private final KnowledgeSourceResponseConverter responseConverter;
    private final QaProperties qaProperties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public QaService(
            ReadyChunkDocumentRetriever readyChunkDocumentRetriever,
            ChatClient chatClient,
            @Qualifier("qaQueryPromptTemplate") PromptTemplate qaQueryPromptTemplate,
            KnowledgeSourceResponseConverter responseConverter,
            QaProperties qaProperties) {
        this.readyChunkDocumentRetriever = readyChunkDocumentRetriever;
        this.chatClient = chatClient;
        this.qaQueryPromptTemplate = qaQueryPromptTemplate;
        this.responseConverter = responseConverter;
        this.qaProperties = qaProperties;
    }

    public QAResponse askQuestion(String query, Long groupId) {
        log.info("Processing QA question: query='{}', groupId={}, config=[maxTokens={}, temperature={}, timeout={}ms]",
                query, groupId, qaProperties.getMaxTokens(), qaProperties.getTemperature(), qaProperties.getChatTimeoutMs());

        try {
            DocumentBundle documentBundle = readyChunkDocumentRetriever.retrieveDocuments(groupId, query);

            if (documentBundle.documents().isEmpty()) {
                log.warn("No relevant context found for question: groupId={}", groupId);
                return new QAResponse(
                        false,
                        "No relevant context found to answer the question",
                        Collections.emptyList(),
                        ""
                );
            }

            String userPrompt = buildUserPrompt(query);
            KnowledgeSourceResponse response = invokeKnowledgeSourceResponse(userPrompt, groupId, documentBundle.documents());
            log.info("QA answer generated successfully: groupId={}, chunks={}", groupId, documentBundle.documents().size());

            List<SourceChunk> sourceChunks = DocumentToSourceChunkConverter.convertAll(documentBundle.documents());
            return new QAResponse(true, "", sourceChunks, response.answer());

        } catch (Exception e) {
            log.error("QA processing failed: groupId={}, error={}", groupId, e.getMessage(), e);
            return new QAResponse(
                    false,
                    "Failed to process question: " + e.getMessage(),
                    Collections.emptyList(),
                    ""
            );
        }
    }

    private KnowledgeSourceResponse invokeKnowledgeSourceResponse(
            String userPrompt, Long groupId, List<Document> documents) throws Exception {
        try {
            String rawResponse = CompletableFuture.supplyAsync(() ->
                chatClient.prompt(userPrompt)
                    .advisors(advisor -> advisor
                        .param("groupId", groupId)
                        .param(ReadyChunkDocumentRetriever.PREFETCHED_DOCUMENTS_CONTEXT_KEY, documents)
                    )
                    .call()
                    .content(),
                executor
            ).get(qaProperties.getChatTimeoutMs(), TimeUnit.MILLISECONDS);

            return responseConverter.convertRawResponse(rawResponse);
        } catch (TimeoutException e) {
            log.error("LLM generation timed out after {}ms for groupId: {}", qaProperties.getChatTimeoutMs(), groupId, e);
            throw new RuntimeException("Request timed out", e);
        } catch (Exception e) {
            log.error("Failed to generate QA response for groupId: {}", groupId, e);
            throw new RuntimeException("Failed to complete QA generation", e);
        }
    }

    private String buildUserPrompt(String query) {
        Map<String, Object> variables = Map.of("query", query);
        return qaQueryPromptTemplate.render(variables);
    }
}
