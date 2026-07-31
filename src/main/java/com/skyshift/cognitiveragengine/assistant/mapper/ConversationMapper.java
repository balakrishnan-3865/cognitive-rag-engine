package com.skyshift.cognitiveragengine.assistant.mapper;

import com.skyshift.cognitiveragengine.assistant.model.entity.AssistantConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationMapper {
    int insert(AssistantConversationEntity conversation);

    AssistantConversationEntity selectByIdAndGroupId(
        @Param("conversationId") Long conversationId,
        @Param("groupId") Long groupId);
}