package com.skyshift.cognitiveragengine.workflows.claims.model.dto;

import com.skyshift.cognitiveragengine.qa.model.SourceChunk;

import java.util.List;

public record AssistantQueryResponse(
        boolean answered,
        String reasonMessage,
        List<SourceChunk> sources,
        String answer
) {}