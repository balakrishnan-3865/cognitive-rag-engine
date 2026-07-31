package com.skyshift.cognitiveragengine.assistant.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AssistantRequest(
        @NotBlank(message = "Message cannot be blank")
        String message,

        @NotNull(message = "GroupId cannot be null")
        @Positive(message = "GroupId must be positive")
        Long groupId
) {}