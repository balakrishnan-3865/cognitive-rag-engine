package com.skyshift.cognitiveragengine.assistant.model.entity;

import com.skyshift.cognitiveragengine.assistant.model.enums.MessageRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMessageEntity {
    private Long id;
    private Long conversationId;
    private Integer sequenceNumber;
    private MessageRole role;
    private String content;
    private String toolName;
    private LocalDateTime createdAt;
}