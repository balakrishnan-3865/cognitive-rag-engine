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
public class AssistantConversationEntity {
    private Long id;
    private Long groupId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}