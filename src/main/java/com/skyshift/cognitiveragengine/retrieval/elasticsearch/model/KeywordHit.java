package com.skyshift.cognitiveragengine.retrieval.elasticsearch.model;

public record KeywordHit(
        Long documentId,
        Long chunkId,
        Integer chunkIndex,
        String fileName,
        String chunkText,
        double rawScore,
        double normalizedScore
) {
}
