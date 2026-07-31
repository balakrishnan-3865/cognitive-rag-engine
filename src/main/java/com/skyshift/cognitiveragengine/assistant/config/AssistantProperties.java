package com.skyshift.cognitiveragengine.assistant.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "assistant")
public class AssistantProperties {
    private int topKDefault = 5;
    private int maxToolLoops = 10;
    private long toolTimeoutMs = 30000;
    private int maxHistoryTurns = 10;
}