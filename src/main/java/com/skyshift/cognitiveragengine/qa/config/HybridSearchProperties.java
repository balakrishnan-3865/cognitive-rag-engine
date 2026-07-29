package com.skyshift.cognitiveragengine.qa.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hybrid-search")
public class HybridSearchProperties {
    private int candidatePoolSize = 30;
}