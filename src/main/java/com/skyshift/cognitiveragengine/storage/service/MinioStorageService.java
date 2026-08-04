package com.skyshift.cognitiveragengine.storage.service;

import com.skyshift.cognitiveragengine.common.exception.StorageException;
import io.minio.BucketExistsArgs;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteResult;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class MinioStorageService implements ObjectStorageService {

    private final MinioClient minioClient;
    private final String defaultBucket;
    private final Object bucketLock = new Object();
    private final Set<String> bucketInitialized =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    public MinioStorageService(MinioClient minioClient, String defaultBucket) {
        this.minioClient = minioClient;
        this.defaultBucket = defaultBucket;
    }

    @Override
    public String getDefaultBucket() {
        return defaultBucket;
    }

    @Override
    public void uploadObject(String bucketName, String key, InputStream content, long size) {
        try {
            ensureBucketExists(bucketName);
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(key)
                    .stream(content, size, -1)
                    .build());
            log.debug("Uploaded object key='{}' to bucket='{}'", key, bucketName);
        } catch (Exception e) {
            log.error("Failed to upload object key='{}' to bucket='{}'", key, bucketName, e);
            throw new StorageException(
                "Failed to upload " + key + " to bucket " + bucketName, e);
        }
    }

    @Override
    public InputStream downloadObject(String bucketName, String key) {
        try {
            ensureBucketExists(bucketName);
            InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(key)
                    .build());
            log.debug("Downloaded object key='{}' from bucket='{}'", key, bucketName);
            return stream;
        } catch (Exception e) {
            log.error("Failed to download object key='{}' from bucket='{}'", key, bucketName, e);
            throw new StorageException(
                "Failed to download " + key + " from bucket " + bucketName, e);
        }
    }

    @Override
    public boolean objectExists(String bucketName, String key) {
        try {
            ensureBucketExists(bucketName);
            minioClient.statObject(
                io.minio.StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(key)
                    .build());
            return true;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("not found")) {
                log.debug("Object key='{}' does not exist in bucket='{}'", key, bucketName);
                return false;
            }
            log.error("Failed to check existence of object key='{}' in bucket='{}'", key, bucketName, e);
            throw new StorageException(
                "Failed to check existence of " + key + " in bucket " + bucketName, e);
        }
    }

    @Override
    public void deleteObject(String bucketName, String key) {
        try {
            ensureBucketExists(bucketName);
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(key)
                    .build());
            log.debug("Deleted object key='{}' from bucket='{}'", key, bucketName);
        } catch (Exception e) {
            log.error("Failed to delete object key='{}' from bucket='{}'", key, bucketName, e);
            throw new StorageException(
                "Failed to delete " + key + " from bucket " + bucketName, e);
        }
    }

    @Override
    public void deleteObjects(String bucketName, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            log.debug("No objects to delete from bucket='{}'", bucketName);
            return;
        }
        try {
            ensureBucketExists(bucketName);
            List<DeleteObject> deleteObjects = keys.stream()
                .map(DeleteObject::new)
                .toList();

            Iterable<Result<DeleteError>> results = minioClient.removeObjects(
                RemoveObjectsArgs.builder()
                    .bucket(bucketName)
                    .objects(deleteObjects)
                    .build());

            for (Result<DeleteError> result : results) {
                DeleteError deleteError = result.get();
                if (deleteError != null) {
                    log.error("Error in deleting object key='{}' from bucket='{}': {}", deleteError.objectName(), bucketName, deleteError.message());
                } else {
                    log.error("Failed to delete object from bucket='{}'", bucketName);
                }
            }
            log.debug("Deleted {} objects from bucket='{}'", keys.size(), bucketName);
        } catch (Exception e) {
            log.error("Failed to delete objects from bucket='{}'", bucketName, e);
            throw new StorageException(
                "Failed to delete objects from bucket " + bucketName, e);
        }
    }

    private void ensureBucketExists(String bucketName) throws Exception {
        // Fast path: already verified
        if (bucketInitialized.contains(bucketName)) {
            return;
        }

        // Synchronized block
        synchronized (bucketLock) {
            // Double-check pattern after acquiring lock
            if (bucketInitialized.contains(bucketName)) {
                return;
            }

            // Check and create
            if (!minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build())) {
                minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Created MinIO bucket='{}'", bucketName);
            }

            // Mark as verified
            bucketInitialized.add(bucketName);
        }
    }
}