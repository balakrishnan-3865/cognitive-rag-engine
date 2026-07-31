package com.skyshift.cognitiveragengine.assistant.mapper;

import com.skyshift.cognitiveragengine.assistant.model.entity.AssistantConversationSummaryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationSummaryMapper {
    AssistantConversationSummaryEntity selectByConversationId(@Param("conversationId") Long conversationId);

    int upsert(AssistantConversationSummaryEntity summary);
}