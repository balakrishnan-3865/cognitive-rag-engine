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
    private int topK = 5;
    private int maxTokens = 2000;
    private double temperature = 0.7;
}