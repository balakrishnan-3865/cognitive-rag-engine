package com.skyshift.cognitiveragengine.qa.service;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.qa.config.QaProperties;
import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
public class ReadyChunkDocumentRetriever implements DocumentRetriever {

    public static final String PREFETCHED_DOCUMENTS_CONTEXT_KEY = "prefetchedDocuments";
    private static final String GROUP_ID_CONTEXT_KEY = "groupId";

    private final HybridChunkRetrievalService hybridChunkRetrievalService;
    private final QaProperties qaProperties;

    public ReadyChunkDocumentRetriever(
            HybridChunkRetrievalService hybridChunkRetrievalService,
            QaProperties qaProperties
    ) {
        this.hybridChunkRetrievalService = hybridChunkRetrievalService;
        this.qaProperties = qaProperties;
    }

    @Override
    public List<Document> retrieve(Query query) {
        Query validQuery = requireQuery(query);
        Long groupId = requireGroupId(validQuery);
        List<Document> prefetchedDocuments = readPrefetchedDocuments(validQuery);
        if (prefetchedDocuments != null) {
            return prefetchedDocuments;
        }
        return retrieve(groupId, query.text());
    }

    public List<Document> retrieve(Long groupId, String question) {
        return retrieveDocuments(groupId, question).documents();
    }

    public DocumentBundle retrieveDocuments(Long groupId, String question) {
        int topK = qaProperties.getTopK();
        return hybridChunkRetrievalService.retrieveRelevantChunks(question, groupId, topK);
    }

    private Query requireQuery(Query query) {
        if (query == null) {
            throw new BusinessException("Search request cannot be null");
        }
        return query;
    }

    private Long requireGroupId(Query query) {
        Object groupId = query.context().get(GROUP_ID_CONTEXT_KEY);
        if (groupId instanceof Number) {
            return requirePositiveGroupId(((Number) groupId).longValue());
        }
        if (groupId instanceof String && StringUtils.hasText((String) groupId)) {
            try {
                return requirePositiveGroupId(Long.parseLong(((String) groupId).trim()));
            } catch (NumberFormatException exception) {
                throw new BusinessException("Invalid groupId format in search context", exception);
            }
        }
        throw new BusinessException("Search context missing groupId");
    }

    private List<Document> readPrefetchedDocuments(Query query) {
        Object documents = query.context().get(PREFETCHED_DOCUMENTS_CONTEXT_KEY);
        if (documents == null) {
            return null;
        }
        if (!(documents instanceof List<?> documentList)) {
            throw new BusinessException("Invalid prefetched evidence format in search context");
        }
        for (Object document : documentList) {
            if (!(document instanceof Document)) {
                throw new BusinessException("Invalid prefetched evidence format in search context");
            }
        }
        @SuppressWarnings("unchecked")
        List<Document> castedDocuments = (List<Document>) documentList;
        return List.copyOf(castedDocuments);
    }

    private Long requirePositiveGroupId(long groupId) {
        if (groupId <= 0) {
            throw new BusinessException("Invalid groupId");
        }
        return groupId;
    }
}