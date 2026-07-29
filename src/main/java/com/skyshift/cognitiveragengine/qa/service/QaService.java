package com.skyshift.cognitiveragengine.qa.service;

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
import java.util.stream.Collectors;

@Slf4j
@Service
public class QaService {

    private final ReadyChunkDocumentRetriever readyChunkDocumentRetriever;
    private final ChatClient chatClient;
    private final PromptTemplate qaQueryPromptTemplate;

    public QaService(
            ReadyChunkDocumentRetriever readyChunkDocumentRetriever,
            ChatClient chatClient,
            @Qualifier("qaQueryPromptTemplate") PromptTemplate qaQueryPromptTemplate
    ) {
        this.readyChunkDocumentRetriever = readyChunkDocumentRetriever;
        this.chatClient = chatClient;
        this.qaQueryPromptTemplate = qaQueryPromptTemplate;
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
            KnowledgeSourceResponse response = chatClient.prompt(userPrompt)
                    .advisors(advisor -> advisor
                            .param("groupId", groupId)
                            .param(ReadyChunkDocumentRetriever.PREFETCHED_DOCUMENTS_CONTEXT_KEY, documentBundle.documents())
                    )
                    .call()
                    .entity(KnowledgeSourceResponse.class);

            log.info("Question answered successfully");

            List<SourceChunk> sourceChunks = convertToSourceChunks(documentBundle.documents());

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

    private List<SourceChunk> convertToSourceChunks(List<Document> documents) {
        return documents.stream()
                .map(doc -> new SourceChunk(
                        doc.getText(),
                        Long.parseLong((String) doc.getMetadata().get("documentId")),
                        Integer.parseInt((String) doc.getMetadata().get("chunkNumber")),
                        Double.parseDouble((String) doc.getMetadata().get("similarity"))
                ))
                .collect(Collectors.toList());
    }

    private String buildUserPrompt(String query) {
        Map<String, Object> variables = Map.of("query", query);
        return qaQueryPromptTemplate.render(variables);
    }
}
