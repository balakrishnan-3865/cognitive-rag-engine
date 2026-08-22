package com.skyshift.cognitiveragengine.document.model.dto;

import java.time.LocalDateTime;

public record DocumentVersionResponse(
    Long id,
    Integer versionNumber,
    Boolean isCurrentVersion,
    String fileName,
    String status,
    LocalDateTime updatedAt
) {}
