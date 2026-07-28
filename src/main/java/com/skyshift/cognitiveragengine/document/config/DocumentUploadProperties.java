package com.skyshift.cognitiveragengine.document.config;

import jakarta.validation.constraints.NotEmpty;
import org.checkerframework.checker.index.qual.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

@ConfigurationProperties("document.upload")
@Validated
public record DocumentUploadProperties(
        @Positive @DefaultValue("52428800") Long maxFileSize,
        @Positive @DefaultValue("255") Integer maxFilenameLength,
        @Positive @DefaultValue("10") Integer maxExtensionLength,
        @NotEmpty @DefaultValue("pdf") Set<String> supportedExtensions
) {}