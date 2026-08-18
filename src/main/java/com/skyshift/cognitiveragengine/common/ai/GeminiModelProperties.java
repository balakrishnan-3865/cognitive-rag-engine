package com.skyshift.cognitiveragengine.common.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared Gemini model identity - one Gemini model/key across every use-case's last-resort
 * fallback tier (classifier, QA, ...), rather than a per-use-case Gemini model choice.
 * Binds to {@code gemini.model.*} in application.yml.
 */
@ConfigurationProperties(prefix = "gemini.model")
public record GeminiModelProperties(String name, String apiKey) {
}
