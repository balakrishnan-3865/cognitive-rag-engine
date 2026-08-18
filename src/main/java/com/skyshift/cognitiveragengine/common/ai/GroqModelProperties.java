package com.skyshift.cognitiveragengine.common.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared Groq model identity - one primary model/key/endpoint across every use-case's
 * primary (tier 1) fallback tier (QA, Assistant/Claims), rather than a per-use-case Groq
 * model choice. The classifier keeps its own independently-tunable
 * {@code classifier.model.*} instead of this, since it was already tuned separately
 * before this fallback chain existed. Binds to {@code groq.model.*} in application.yml.
 */
@ConfigurationProperties(prefix = "groq.model")
public record GroqModelProperties(String name, String apiKey, String baseUrl) {
}
