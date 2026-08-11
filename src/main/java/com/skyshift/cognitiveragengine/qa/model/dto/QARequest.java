package com.skyshift.cognitiveragengine.qa.model.dto;

import jakarta.validation.constraints.NotBlank;

public record QARequest(
        @NotBlank(message = "Query cannot be blank")
        String query,
        Long documentId
) {}
