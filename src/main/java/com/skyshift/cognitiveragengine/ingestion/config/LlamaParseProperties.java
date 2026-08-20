package com.skyshift.cognitiveragengine.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Mirrors {@code DoclingProperties}'s shape. {@code apiKey} has no default — it is a secret that
 * must come from {@code LLAMA_CLOUD_API_KEY}; missing/blank only fails startup when
 * {@code parser.strategy=llama} is active (see {@link LlamaParseClientConfiguration}).
 * {@code tier}/{@code version} default to values confirmed required by the API in Verification
 * (02-verification.md Q4): {@code fast} is incompatible with the {@code items} expand this
 * strategy depends on, and {@code balanced} is rejected outright.
 */
@ConfigurationProperties(prefix = "llamaparse")
public record LlamaParseProperties(
        @DefaultValue("https://api.cloud.llamaindex.ai") String baseUrl,
        String apiKey,
        @DefaultValue("cost_effective") String tier,
        @DefaultValue("latest") String version,
        @DefaultValue("4000") long pollIntervalMs,
        @DefaultValue("150") int pollMaxAttempts
) {}
