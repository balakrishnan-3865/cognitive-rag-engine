package com.skyshift.cognitiveragengine.document.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

@ConfigurationProperties("document.upload")
@Validated
public record DocumentUploadProperties(
        @DefaultValue("20MB") DataSize maxFileSize,
        @DefaultValue("255") Integer maxFilenameLength,
        @DefaultValue("10") Integer maxExtensionLength,
        @NotEmpty @DefaultValue("pdf") Set<String> supportedExtensions
) {}