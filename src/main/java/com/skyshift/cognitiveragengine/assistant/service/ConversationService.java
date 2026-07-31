package com.skyshift.cognitiveragengine.assistant.service;

import com.skyshift.cognitiveragengine.assistant.mapper.ConversationMapper;
import com.skyshift.cognitiveragengine.assistant.mapper.ConversationMessageMapper;
import com.skyshift.cognitiveragengine.assistant.mapper.ConversationSummaryMapper;
import com.skyshift.cognitiveragengine.assistant.model.entity.AssistantConversationEntity;
import com.skyshift.cognitiveragengine.assistant.model.entity.AssistantConversationSummaryEntity;
import com.skyshift.cognitiveragengine.assistant.model.entity.ConversationMessageEntity;
import com.skyshift.cognitiveragengine.assistant.model.enums.MessageRole;
import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationSummaryMapper conversationSummaryMapper;

    public ConversationService(
            ConversationMapper conversationMapper,
            ConversationMessageMapper conversationMessageMapper,
            ConversationSummaryMapper conversationSummaryMapper
    ) {
        this.conversationMapper = conversationMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.conversationSummaryMapper = conversationSummaryMapper;
    }

    public Long getOrCreateConversation(Long conversationId, Long groupId) {
        if (conversationId == null) {
            LocalDateTime now = LocalDateTime.now();
            AssistantConversationEntity conversation = AssistantConversationEntity.builder()
                    .groupId(groupId)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            conversationMapper.insert(conversation);
            return conversation.getId();
        }

        AssistantConversationEntity existing = conversationMapper.selectByIdAndGroupId(conversationId, groupId);
        if (existing == null) {
            throw new BusinessException("Conversation " + conversationId + " not found for group " + groupId);
        }
        return existing.getId();
    }

    public List<Message> loadHistory(Long conversationId, int maxTurns) {
        // Tool audit rows share the same sequence with user/assistant turns and are never replayed,
        // so a tool-call-heavy window can retain fewer than maxTurns real turns - acceptable for the
        // message-count-based cap phase 1 targets; phase 2's token-based trigger supersedes this.
        List<Message> history = new ArrayList<>();

        AssistantConversationSummaryEntity summary = conversationSummaryMapper.selectByConversationId(conversationId);
        if (summary != null) {
            // Leading context message, ahead of the verbatim buffer, so it isn't lost in the middle
            // of the message list - see docs/assistant-memory-phase2-summarization.md.
            history.add(new SystemMessage("Summary of earlier conversation:\n" + summary.getSummaryText()));
        }

        conversationMessageMapper.selectRecentByConversationId(conversationId, maxTurns * 2).stream()
                .filter(entity -> entity.getRole() == MessageRole.USER || entity.getRole() == MessageRole.ASSISTANT)
                .map(ConversationService::toMessage)
                .forEach(history::add);

        return history;
    }

    public void appendMessage(Long conversationId, MessageRole role, String content, String toolName) {
        ConversationMessageEntity message = ConversationMessageEntity.builder()
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .toolName(toolName)
                .createdAt(LocalDateTime.now())
                .build();
        conversationMessageMapper.insert(message);
    }

    private static Message toMessage(ConversationMessageEntity entity) {
        return entity.getRole() == MessageRole.USER
                ? new UserMessage(entity.getContent())
                : new AssistantMessage(entity.getContent());
    }
}