package com.skyshift.cognitiveragengine.retrieval.vectorstore;

import com.skyshift.cognitiveragengine.qa.config.RetrievalProperties;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.exception.VectorSearchException;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.model.VectorHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VectorSearchService {

    private final VectorStore vectorStore;
    private final RetrievalProperties retrievalProperties;

    public VectorSearchService(VectorStore vectorStore, RetrievalProperties retrievalProperties) {
        this.vectorStore = vectorStore;
        this.retrievalProperties = retrievalProperties;
    }

    public List<VectorHit> search(String query, Long groupId, List<Long> documentIds, int topK) {
        log.info("Starting vector search: query='{}', groupId={}, topK={}",
                query, groupId, topK);

        validateSearchParams(query, groupId, topK);

        if (documentIds.isEmpty()) {
            log.debug("No current READY documents for groupId={}, skipping vector search", groupId);
            return List.of();
        }

        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.and(
                    b.eq("groupId", groupId),
                    b.in("documentId", new ArrayList<Object>(documentIds))
            ).build();

            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(retrievalProperties.getDense().getSimilarityThreshold())
                    .filterExpression(filter)
                    .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);
            log.debug("Retrieved {} documents from vector store for groupId: {}",
                    results.size(), groupId);

            List<VectorHit> hits = results.stream()
                    .map(doc -> toVectorHit(doc, groupId))
                    .collect(Collectors.toList());

            log.info("Vector search completed: found {} results for groupId: {}",
                    hits.size(), groupId);

            return hits;
        } catch (VectorSearchException e) {
            throw e;
        } catch (Exception e) {
            log.error("Vector search failed for query='{}', groupId={}: {}",
                    query, groupId, e.getMessage(), e);
            throw new VectorSearchException("Vector search failed: " + e.getMessage(), e);
        }
    }

    private void validateSearchParams(String query, Long groupId, int topK) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be null or empty");
        }
        if (query.length() > 2000) {
            throw new IllegalArgumentException("Query exceeds maximum length of 2000 characters");
        }
        if (groupId == null || groupId <= 0) {
            throw new IllegalArgumentException("GroupId must be a positive non-null value");
        }
        if (topK < 1 || topK > 1000) {
            throw new IllegalArgumentException("TopK must be between 1 and 1000");
        }
    }

    private VectorHit toVectorHit(Document document, Long expectedGroupId) {
        try {
            Map<String, Object> metadata = document.getMetadata();

            Long documentId = extractLong(metadata, "documentId");
            Long chunkId = extractLong(metadata, "chunkId");
            Long returnedGroupId = extractLong(metadata, "groupId");
            Integer chunkNumber = extractInteger(metadata, "chunkNumber");
//            Integer startPosition = extractInteger(metadata, "startPosition");
//            Integer endPosition = extractInteger(metadata, "endPosition");

            if (!expectedGroupId.equals(returnedGroupId)) {
                throw new VectorSearchException(
                        "Cross-tenant violation: expected groupId " + expectedGroupId +
                                " but got " + returnedGroupId + " for document id: " + document.getId());
            }

            Double score = extractScore(document);

            return new VectorHit(
                    document.getId(),
                    document.getText(),
                    documentId,
                    chunkId,
                    returnedGroupId,
                    chunkNumber,
//                    startPosition,
//                    endPosition,
                    score
            );
        } catch (ClassCastException e) {
            log.error("Type conversion error for document {}: {}",
                    document.getId(), e.getMessage(), e);
            throw new VectorSearchException(
                    "Failed to convert document metadata types for document " + document.getId(), e);
        }
    }

    private Long extractLong(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            throw new VectorSearchException("Required metadata field '" + key + "' is null");
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new ClassCastException("Cannot convert " + key + " to Long: " + value.getClass().getName());
    }

    private Integer extractInteger(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) {
            throw new VectorSearchException("Required metadata field '" + key + "' is null");
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        throw new ClassCastException("Cannot convert " + key + " to Integer: " + value.getClass().getName());
    }

    private Double extractScore(Document document) {
        Double score = document.getScore();
        return score != null ? score : 0.0;
    }
}