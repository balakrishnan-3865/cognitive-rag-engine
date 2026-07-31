package com.skyshift.cognitiveragengine.assistant.service;

import com.skyshift.cognitiveragengine.assistant.config.AssistantProperties;
import com.skyshift.cognitiveragengine.assistant.mapper.ConversationMessageMapper;
import com.skyshift.cognitiveragengine.assistant.mapper.ConversationSummaryMapper;
import com.skyshift.cognitiveragengine.assistant.model.entity.AssistantConversationSummaryEntity;
import com.skyshift.cognitiveragengine.assistant.model.entity.ConversationMessageEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Folds turns older than the phase-1 verbatim buffer window into a single rolling summary once a
 * conversation has enough unsummarized messages - see docs/assistant-memory-phase2-summarization.md.
 */
@Slf4j
@Service
public class ConversationSummaryService {

    private static final String NO_PREVIOUS_SUMMARY_PLACEHOLDER = "(none yet)";

    private final ConversationSummaryMapper conversationSummaryMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final ChatModel chatModel;
    private final PromptTemplate assistantConversationSummaryTemplate;
    private final AssistantProperties assistantProperties;

    public ConversationSummaryService(
            ConversationSummaryMapper conversationSummaryMapper,
            ConversationMessageMapper conversationMessageMapper,
            ChatModel chatModel,
            @Qualifier("assistantConversationSummaryTemplate") PromptTemplate assistantConversationSummaryTemplate,
            AssistantProperties assistantProperties
    ) {
        this.conversationSummaryMapper = conversationSummaryMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.chatModel = chatModel;
        this.assistantConversationSummaryTemplate = assistantConversationSummaryTemplate;
        this.assistantProperties = assistantProperties;
    }

    public void maybeSummarize(Long conversationId) {
        AssistantConversationSummaryEntity existingSummary = conversationSummaryMapper.selectByConversationId(conversationId);
        int summarizedThrough = existingSummary == null ? 0 : existingSummary.getSummarizedThroughSequenceNumber();

        List<ConversationMessageEntity> unsummarized =
                conversationMessageMapper.selectAfterSequenceNumber(conversationId, summarizedThrough);
        if (unsummarized.size() <= assistantProperties.getSummarizationTriggerMessageCount()) {
            return;
        }

        // Same maxTurns * 2 approximation ConversationService.loadHistory uses for its verbatim window -
        // those messages stay out of the summary so they aren't summarized and replayed verbatim at once.
        int verbatimWindowSize = assistantProperties.getMaxHistoryTurns() * 2;
        if (unsummarized.size() <= verbatimWindowSize) {
            return;
        }

        List<ConversationMessageEntity> toSummarize =
                unsummarized.subList(0, unsummarized.size() - verbatimWindowSize);

        try {
            String previousSummary = existingSummary == null ? NO_PREVIOUS_SUMMARY_PLACEHOLDER : existingSummary.getSummaryText();
            String prompt = assistantConversationSummaryTemplate.render(Map.of(
                    "previousSummary", previousSummary,
                    "newMessages", formatMessages(toSummarize)
            ));
            String updatedSummary = chatModel.call(prompt);
            int newCutoff = toSummarize.get(toSummarize.size() - 1).getSequenceNumber();

            conversationSummaryMapper.upsert(AssistantConversationSummaryEntity.builder()
                    .conversationId(conversationId)
                    .summaryText(updatedSummary)
                    .summarizedThroughSequenceNumber(newCutoff)
                    .updatedAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Failed to summarize conversation {}, keeping previous summary: {}", conversationId, e.getMessage(), e);
        }
    }

    private String formatMessages(List<ConversationMessageEntity> messages) {
        return messages.stream()
                .map(message -> message.getRole() + ": " + message.getContent())
                .collect(Collectors.joining("\n"));
    }
}