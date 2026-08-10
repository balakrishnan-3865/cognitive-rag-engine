package com.skyshift.cognitiveragengine.auth.model.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @NotBlank(message = "RefreshToken cannot be blank")
        String refreshToken
) {}
