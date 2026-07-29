package com.skyshift.cognitiveragengine.qa.model.dto;

import com.skyshift.cognitiveragengine.qa.model.SourceChunk;

import java.util.List;

public record QAResponse(
        boolean answered,
        String reasonMessage,
        List<SourceChunk> sources,
        String answer
) {}