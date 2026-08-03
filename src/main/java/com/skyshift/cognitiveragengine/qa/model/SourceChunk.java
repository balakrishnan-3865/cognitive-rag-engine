package com.skyshift.cognitiveragengine.qa.model;

public record SourceChunk(
        String text,
        Long chunkId,
        Long documentId,
        Integer chunkNumber,
        Double similarity,
        String source
) {}