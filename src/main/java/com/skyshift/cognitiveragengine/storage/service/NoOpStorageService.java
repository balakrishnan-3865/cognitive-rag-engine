package com.skyshift.cognitiveragengine.storage.service;

import com.skyshift.cognitiveragengine.common.exception.StorageException;

import java.io.InputStream;
import java.util.List;

public class NoOpStorageService implements ObjectStorageService {

    private static final String MINIO_NOT_CONFIGURED = "MinIO storage is not configured. ";

    @Override
    public void uploadObject(String bucketName, String key, InputStream content, long size) {
        throw new StorageException(
            "Cannot upload object key='" + key + "' to bucket='" + bucketName + "'. " + MINIO_NOT_CONFIGURED);
    }

    @Override
    public InputStream downloadObject(String bucketName, String key) {
        throw new StorageException(
            "Cannot download object key='" + key + "' from bucket='" + bucketName + "'. " + MINIO_NOT_CONFIGURED);
    }

    @Override
    public boolean objectExists(String bucketName, String key) {
        throw new StorageException(
            "Cannot check existence of object key='" + key + "' in bucket='" + bucketName + "'. " + MINIO_NOT_CONFIGURED);
    }

    @Override
    public void deleteObject(String bucketName, String key) {
        throw new StorageException(
            "Cannot delete object key='" + key + "' from bucket='" + bucketName + "'. " + MINIO_NOT_CONFIGURED);
    }

    @Override
    public void deleteObjects(String bucketName, List<String> keys) {
        throw new StorageException(
            "Cannot delete objects from bucket='" + bucketName + "'. " + MINIO_NOT_CONFIGURED);
    }
}