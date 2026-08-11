package com.skyshift.cognitiveragengine.user.model.dto;

public record UserSummaryResponse(
    Long id,
    Long groupId,
    String username,
    String email,
    String firstName,
    String lastName,
    String role,
    boolean enabled
) {}
