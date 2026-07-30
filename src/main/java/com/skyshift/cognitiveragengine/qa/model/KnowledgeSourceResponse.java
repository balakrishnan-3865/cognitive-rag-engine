package com.skyshift.cognitiveragengine.qa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeSourceResponse(
        boolean answered,
        String answer
) {
}