package com.skyshift.cognitiveragengine.document.model.dto;

import org.springframework.web.multipart.MultipartFile;

/**
 * Document upload request binding with file and groupId.
 */
public record DocumentUploadRequest(
    MultipartFile file,
    Long groupId
) {
    public DocumentUploadRequest {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required and cannot be empty");
        }
        if (groupId == null || groupId <= 0) {
            throw new IllegalArgumentException("groupId must be a positive value");
        }
    }
}