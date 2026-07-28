package com.skyshift.cognitiveragengine.retrieval.vectorstore.model;

public record VectorHit(
    String id,
    String content,
    Long documentId,
    Long groupId,
    Integer chunkNumber,
    Integer startPosition,
    Integer endPosition,
    Double score
) {}