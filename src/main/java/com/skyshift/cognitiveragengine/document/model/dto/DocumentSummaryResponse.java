package com.skyshift.cognitiveragengine.document.model.dto;

import java.time.LocalDateTime;

public record DocumentSummaryResponse(
    Long id,
    String title,
    String latestVersionLabel,
    String status,
    LocalDateTime updatedAt
) {}
