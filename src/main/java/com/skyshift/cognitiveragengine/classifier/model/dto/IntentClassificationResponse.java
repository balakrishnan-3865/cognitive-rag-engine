package com.skyshift.cognitiveragengine.classifier.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;

/**
 * Structured response from the intent classifier LLM.
 * Used with Spring AI ChatClient.entity() for type-safe JSON parsing.
 * Also serves as the result of intent classification with timing metadata.
 */
public record IntentClassificationResponse(
    /**
     * The determined routing intent for the query
     */
    @JsonProperty("intent")
    RoutingIntent intent,

    /**
     * Confidence score for the classification (0.0 to 1.0)
     */
    @JsonProperty("confidence")
    double confidence,

    /**
     * The reasoning provided by the model for this classification
     */
    @JsonProperty("reasoning")
    String reasoning
) {}