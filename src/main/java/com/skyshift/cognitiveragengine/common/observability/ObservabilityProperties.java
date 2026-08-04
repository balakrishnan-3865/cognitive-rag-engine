package com.skyshift.cognitiveragengine.common.observability;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {
    private boolean captureContent = true;
}