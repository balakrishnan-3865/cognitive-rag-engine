package com.skyshift.cognitiveragengine.qa.service;

import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.VectorSearchService;
import com.skyshift.cognitiveragengine.retrieval.vectorstore.model.VectorHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class HybridChunkRetrievalService {

    private final VectorSearchService vectorSearchService;

    public HybridChunkRetrievalService(
            VectorSearchService vectorSearchService
    ) {
        this.vectorSearchService = vectorSearchService;
    }

    public DocumentBundle retrieveRelevantChunks(String query, Long groupId, int topK) {

        List<VectorHit> vectorHits = vectorSearchService.search(query, groupId, topK);
        log.debug("Retrieved {} chunks from vector store", vectorHits.size());

        List<Document> documents = convertToDocuments(vectorHits);
        return new DocumentBundle(documents);
    }

    private List<Document> convertToDocuments(List<VectorHit> vectorHits) {
        return vectorHits.stream()
                .map(hit -> new Document(
                        hit.content(),
                        Map.of(
                                "documentId", hit.documentId().toString(),
                                "chunkNumber", hit.chunkNumber().toString(),
                                "similarity", hit.score().toString()
                        )
                ))
                .collect(Collectors.toList());
    }
}