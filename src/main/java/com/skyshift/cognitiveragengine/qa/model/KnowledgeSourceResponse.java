package com.skyshift.cognitiveragengine.qa.model;

public record KnowledgeSourceResponse(
        boolean answered,
        String answer
) {}