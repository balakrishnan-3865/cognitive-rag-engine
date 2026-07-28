package com.skyshift.cognitiveragengine.storage.config;

import com.skyshift.cognitiveragengine.storage.service.MinioStorageService;
import com.skyshift.cognitiveragengine.storage.service.NoOpStorageService;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

@Slf4j
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioClientConfiguration {

    @Bean
    public MinioClient minioClient(MinioProperties props) {
        if (!props.isConfigured()) {
            log.error("MinIO configuration incomplete or missing. Storage operations will be no-ops.");
            return null;
        }
        try {
            MinioClient client = MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
            log.info("MinIO client initialized successfully. Endpoint: {}", props.endpoint());
            return client;
        } catch (Exception e) {
            log.error("Failed to initialize MinIO client with endpoint: {}", props.endpoint(), e);
            throw new IllegalStateException("Failed to initialize MinIO client", e);
        }
    }

    @Bean
    public ObjectStorageService objectStorageService(
            MinioProperties props,
            @Nullable MinioClient minioClient) {

        if (!props.isConfigured() || minioClient == null) {
            log.warn("Using NoOp object storage service. MinIO is not configured. Default bucket: {}",
                props.getEffectiveBucket());
            return new NoOpStorageService(props);
        }

        log.info("Using MinIO object storage service with bucket: {}", props.getEffectiveBucket());
        return new MinioStorageService(minioClient, props.getEffectiveBucket());
    }
}