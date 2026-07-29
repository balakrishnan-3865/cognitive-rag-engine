package com.skyshift.cognitiveragengine.qa.model;

public record SourceChunk(
        String text,
        Long documentId,
        Integer chunkNumber,
        Double similarity
) {}