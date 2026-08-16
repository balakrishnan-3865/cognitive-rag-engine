package com.skyshift.cognitiveragengine.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage.minio")
public record MinioProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String bucket
) {
    public static final String DEFAULT_BUCKET = "cognitive-rag-engine";

    public boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank()
            && accessKey != null && !accessKey.isBlank()
            && secretKey != null && !secretKey.isBlank();
    }

    public String getEffectiveBucket() {
        return (bucket != null && !bucket.isBlank()) ? bucket : DEFAULT_BUCKET;
    }
}
