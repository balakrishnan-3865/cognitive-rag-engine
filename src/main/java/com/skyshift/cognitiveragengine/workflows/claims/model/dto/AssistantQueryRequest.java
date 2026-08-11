package com.skyshift.cognitiveragengine.workflows.claims.model.dto;

import jakarta.validation.constraints.NotBlank;

public record AssistantQueryRequest(
        @NotBlank(message = "Query cannot be blank")
        String query,

        Long documentId
) {}
