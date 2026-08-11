package com.skyshift.cognitiveragengine.document.model.dto;

import org.springframework.web.multipart.MultipartFile;

/**
 * Document upload request binding with file. groupId/uploadedUserId come from the authenticated
 * principal, not the client - see DocumentController.
 */
public record DocumentUploadRequest(
    MultipartFile file
) {
    public DocumentUploadRequest {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required and cannot be empty");
        }
    }
}