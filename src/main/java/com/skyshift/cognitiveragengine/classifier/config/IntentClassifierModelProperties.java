package com.skyshift.cognitiveragengine.classifier.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the intent classifier model.
 * Binds to classifier.model.* in application.yml
 */
@ConfigurationProperties(prefix = "classifier.model")
public record IntentClassifierModelProperties(
        String name,
        String apiKey,
        String baseUrl,
        Options options
) {
    public record Options(Double temperature, Integer maxTokens) {
        public Options {
            if (temperature == null) temperature = 0.1;
            if (maxTokens == null) maxTokens = 200;
        }
    }
}