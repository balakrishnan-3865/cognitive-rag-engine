package com.skyshift.cognitiveragengine.auth.model.dto;

public record TokenPairResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {}
