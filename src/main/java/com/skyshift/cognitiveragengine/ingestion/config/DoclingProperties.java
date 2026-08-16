package com.skyshift.cognitiveragengine.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "docling")
public record DoclingProperties(
        @DefaultValue("http://localhost:5001") String baseUrl
) {}
