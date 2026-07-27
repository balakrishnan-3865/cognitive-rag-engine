package com.skyshift.cognitiveragengine.storage.service;

import java.io.InputStream;
import java.util.List;

public interface ObjectStorageService {

    void uploadObject(String bucketName, String key, InputStream content, long size);

    InputStream downloadObject(String bucketName, String key);

    boolean objectExists(String bucketName, String key);

    void deleteObject(String bucketName, String key);

    void deleteObjects(String bucketName, List<String> keys);
}