package com.skyshift.cognitiveragengine.assistant.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantConversationSummaryEntity {
    private Long conversationId;
    private String summaryText;
    private Integer summarizedThroughSequenceNumber;
    private LocalDateTime updatedAt;
}