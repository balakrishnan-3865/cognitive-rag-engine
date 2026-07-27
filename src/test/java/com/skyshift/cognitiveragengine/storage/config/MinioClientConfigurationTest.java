package com.skyshift.cognitiveragengine.storage.config;

import com.skyshift.cognitiveragengine.storage.service.MinioStorageService;
import com.skyshift.cognitiveragengine.storage.service.NoOpStorageService;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class MinioClientConfigurationTest {

    private final MinioClientConfiguration configuration = new MinioClientConfiguration();

    @Test
    void testMinioClientBean_WithCompleteConfiguration() {
        MinioProperties props = new MinioProperties(
            "http://localhost:9000",
            "minioadmin",
            "minioadmin",
            "test-bucket"
        );

        MinioClient client = configuration.minioClient(props);

        assertInstanceOf(MinioClient.class, client);
    }

    @Test
    void testMinioClientBean_WithMissingEndpoint() {
        MinioProperties props = new MinioProperties(
            null,
            "minioadmin",
            "minioadmin",
            "test-bucket"
        );

        MinioClient client = configuration.minioClient(props);

        assertNull(client);
    }

    @Test
    void testMinioClientBean_WithMissingAccessKey() {
        MinioProperties props = new MinioProperties(
            "http://localhost:9000",
            null,
            "minioadmin",
            "test-bucket"
        );

        MinioClient client = configuration.minioClient(props);

        assertNull(client);
    }

    @Test
    void testMinioClientBean_WithMissingSecretKey() {
        MinioProperties props = new MinioProperties(
            "http://localhost:9000",
            "minioadmin",
            null,
            "test-bucket"
        );

        MinioClient client = configuration.minioClient(props);

        assertNull(client);
    }

    @Test
    void testMinioClientBean_WithMissingBucket() {
        MinioProperties props = new MinioProperties(
            "http://localhost:9000",
            "minioadmin",
            "minioadmin",
            null
        );

        MinioClient client = configuration.minioClient(props);

        assertNull(client);
    }

    @Test
    void testObjectStorageService_WithCompleteConfiguration() {
        MinioProperties props = new MinioProperties(
            "http://localhost:9000",
            "minioadmin",
            "minioadmin",
            "test-bucket"
        );
        MinioClient minioClient = configuration.minioClient(props);

        ObjectStorageService service = configuration.objectStorageService(props, minioClient);

        assertInstanceOf(MinioStorageService.class, service);
    }

    @Test
    void testObjectStorageService_WithMissingConfiguration() {
        MinioProperties props = new MinioProperties(
            null,
            "minioadmin",
            "minioadmin",
            "test-bucket"
        );

        ObjectStorageService service = configuration.objectStorageService(props, null);

        assertInstanceOf(NoOpStorageService.class, service);
    }

    @Test
    void testObjectStorageService_WithNullMinioClient() {
        MinioProperties props = new MinioProperties(
            "http://localhost:9000",
            "minioadmin",
            "minioadmin",
            "test-bucket"
        );

        ObjectStorageService service = configuration.objectStorageService(props, null);

        assertInstanceOf(NoOpStorageService.class, service);
    }

    @Test
    void testMinioProperties_IsConfigured_AllFieldsSet() {
        MinioProperties props = new MinioProperties(
            "http://localhost:9000",
            "minioadmin",
            "minioadmin",
            "test-bucket"
        );

        assertInstanceOf(Boolean.class, props.isConfigured());
    }

    @Test
    void testMinioProperties_IsConfigured_EmptyStrings() {
        MinioProperties props = new MinioProperties(
            "",
            "",
            "",
            ""
        );

        assertInstanceOf(Boolean.class, props.isConfigured());
    }
}