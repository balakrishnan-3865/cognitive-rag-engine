package com.skyshift.cognitiveragengine.auth.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@ConfigurationProperties("cors")
@Validated
public record CorsProperties(
        @NotEmpty List<String> allowedOrigins
) {}
