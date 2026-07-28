package com.skyshift.cognitiveragengine.document.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for document module.
 * Registers configuration properties and beans for document upload functionality.
 */
@Configuration
@EnableConfigurationProperties(DocumentUploadProperties.class)
public class DocumentConfiguration {
}