package com.skyshift.cognitiveragengine.assistant.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record AssistantRequest(
        @NotBlank(message = "Message cannot be blank")
        String message,

        @Positive(message = "ConversationId must be positive")
        Long conversationId
) {}
