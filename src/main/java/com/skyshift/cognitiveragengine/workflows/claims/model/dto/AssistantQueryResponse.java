package com.skyshift.cognitiveragengine.workflows.claims.model.dto;

public record AssistantQueryResponse(
        boolean answered,
        String reasonMessage,
        String answer
) {}