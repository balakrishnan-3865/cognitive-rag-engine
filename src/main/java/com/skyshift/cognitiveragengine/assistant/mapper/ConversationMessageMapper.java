package com.skyshift.cognitiveragengine.assistant.mapper;

import com.skyshift.cognitiveragengine.assistant.model.entity.ConversationMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConversationMessageMapper {
    int insert(ConversationMessageEntity message);

    /**
     * Returns the most recent {@code limit} messages for the conversation, oldest first.
     */
    List<ConversationMessageEntity> selectRecentByConversationId(
        @Param("conversationId") Long conversationId,
        @Param("limit") int limit);

    /**
     * Returns messages with sequence_number greater than {@code afterSequenceNumber}, oldest first.
     */
    List<ConversationMessageEntity> selectAfterSequenceNumber(
        @Param("conversationId") Long conversationId,
        @Param("afterSequenceNumber") int afterSequenceNumber);
}