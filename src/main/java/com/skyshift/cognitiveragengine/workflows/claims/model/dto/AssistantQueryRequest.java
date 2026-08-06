package com.skyshift.cognitiveragengine.workflows.claims.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AssistantQueryRequest(
        @NotNull(message = "UserId cannot be null")
        @Positive(message = "UserId must be positive")
        Long userId,

        @NotNull(message = "GroupId cannot be null")
        @Positive(message = "GroupId must be positive")
        Long groupId,

        @NotBlank(message = "Query cannot be blank")
        String query
) {}