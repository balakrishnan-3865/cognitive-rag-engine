package com.skyshift.cognitiveragengine.retrieval.elasticsearch.model;

public record SparseChunkDto(
    Long chunkId,
    Long groupId,
    Long documentId,
    int chunkIndex,
    String fileName,
    String chunkText,
    String status,
    boolean deleted

) {}