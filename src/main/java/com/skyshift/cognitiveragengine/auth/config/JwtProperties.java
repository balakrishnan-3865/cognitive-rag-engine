package com.skyshift.cognitiveragengine.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("jwt")
@Validated
public record JwtProperties(
        @NotBlank String secret,
        @DefaultValue("900") long accessTokenTtlSeconds,
        @DefaultValue("604800") long refreshTokenTtlSeconds
) {}
