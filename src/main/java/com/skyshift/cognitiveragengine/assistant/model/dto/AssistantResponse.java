package com.skyshift.cognitiveragengine.assistant.model.dto;

import com.skyshift.cognitiveragengine.qa.model.SourceChunk;

import java.util.List;

public record AssistantResponse(
        boolean answered,
        String reasonMessage,
        List<SourceChunk> sources,
        String answer,
        Long conversationId
) {}