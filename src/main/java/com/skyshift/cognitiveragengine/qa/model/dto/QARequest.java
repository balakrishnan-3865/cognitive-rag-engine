package com.skyshift.cognitiveragengine.qa.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record QARequest(
        @NotBlank(message = "Query cannot be blank")
        String query,

        @NotNull(message = "GroupId cannot be null")
        @Positive(message = "GroupId must be positive")
        Long groupId
) {}