package com.skyshift.cognitiveragengine.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the QA answer model - independent of the app-wide
 * {@code spring.ai.model.chat} default, following the same dedicated-bean pattern as
 * {@code classifier.model.*}. Binds to {@code qa.model.*} in application.yml.
 */
@ConfigurationProperties(prefix = "qa.model")
public record QaModelProperties(String name, String apiKey) {
}
