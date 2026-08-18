package com.skyshift.cognitiveragengine.common.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openrouter")
public record OpenRouterProperties(String name, String apiKey, String baseUrl) {
}
