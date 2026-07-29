package com.skyshift.cognitiveragengine.qa.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "qa")
public class QaProperties {
    private int topKDefault = 5;
    private double similarityThreshold = 0.5;
    private long chatTimeoutMs = 30000;
    private double temperature = 0.7;
    private int maxTokens = 2000;
}