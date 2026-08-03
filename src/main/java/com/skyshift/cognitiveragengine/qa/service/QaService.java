package com.skyshift.cognitiveragengine.qa.service;

import com.skyshift.cognitiveragengine.common.converter.DocumentToSourceChunkConverter;
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

@Slf4j
@Service
public class QaService {

    private final ReadyChunkDocumentRetriever readyChunkDocumentRetriever;
    private final ChatClient chatClient;
    private final PromptTemplate qaQueryPromptTemplate;
    private final KnowledgeSourceResponseConverter responseConverter;

    public QaService(
            ReadyChunkDocumentRetriever readyChunkDocumentRetriever,
            ChatClient chatClient,
            @Qualifier("qaQueryPromptTemplate") PromptTemplate qaQueryPromptTemplate,
            KnowledgeSourceResponseConverter responseConverter
    ) {
        this.readyChunkDocumentRetriever = readyChunkDocumentRetriever;
        this.chatClient = chatClient;
        this.qaQueryPromptTemplate = qaQueryPromptTemplate;
        this.responseConverter = responseConverter;
    }

    public QAResponse askQuestion(String query, Long groupId) {
        log.info("Processing question: query='{}', groupId={}", query, groupId);

        try {
            log.debug("Retrieving relevant chunks for query: {}", query);
            DocumentBundle documentBundle = readyChunkDocumentRetriever.retrieveDocuments(groupId, query);

            if (documentBundle.documents().isEmpty()) {
                log.warn("No relevant chunks found for query: {} in groupId: {}", query, groupId);
                return new QAResponse(
                        false,
                        "No relevant context found to answer the question",
                        Collections.emptyList(),
                        ""
                );
            }

            log.debug("Building user prompt with query and {} context chunks", documentBundle.documents().size());
            String userPrompt = buildUserPrompt(query);

            log.debug("Calling ChatClient with advisors to generate answer");
            KnowledgeSourceResponse response = invokeKnowledgeSourceResponse(userPrompt, groupId, documentBundle.documents());

            log.info("Question answered successfully");

            List<SourceChunk> sourceChunks = DocumentToSourceChunkConverter.convertAll(documentBundle.documents());

            return new QAResponse(true, "", sourceChunks, response.answer());

        } catch (Exception e) {
            log.error("Error processing question: query='{}', groupId={}: {}",
                    query, groupId, e.getMessage(), e);
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

        // Raw response + lenient conversion: reasoning models (e.g. deepseek-r1) prepend a
        // <think> preamble that breaks strict entity() deserialization, so entity() isn't
        // used here - it forced a second full LLM call on every request to fall back to this
        // same conversion anyway. KnowledgeSourceResponseConverter already handles both clean
        // JSON and preamble-polluted responses.
        log.debug("Invoking ChatClient and converting raw response");
        String rawResponse = chatClient.prompt(userPrompt)
                .advisors(advisor -> advisor
                        .param("groupId", groupId)
                        .param(ReadyChunkDocumentRetriever.PREFETCHED_DOCUMENTS_CONTEXT_KEY, documents)
                )
                .call()
                .content();

        return responseConverter.convertRawResponse(rawResponse);
    }

    private String buildUserPrompt(String query) {
        Map<String, Object> variables = Map.of("query", query);
        return qaQueryPromptTemplate.render(variables);
    }
}
