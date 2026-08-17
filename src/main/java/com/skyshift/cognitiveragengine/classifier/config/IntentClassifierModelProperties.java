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
            // gpt-oss-20b's hidden reasoning tokens still count against this budget (verified live:
            // a real classification call used 288 reasoning tokens before ~47 tokens of visible JSON),
            // so this must have headroom well beyond the visible 3-field response.
            if (maxTokens == null) maxTokens = 800;
        }
    }
}