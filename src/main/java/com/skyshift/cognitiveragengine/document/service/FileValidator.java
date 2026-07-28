package com.skyshift.cognitiveragengine.document.service;

import com.skyshift.cognitiveragengine.document.config.DocumentUploadProperties;
import com.skyshift.cognitiveragengine.document.exception.DocumentUploadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
public class FileValidator {
    private final DocumentUploadProperties uploadProperties;

    public FileValidator(DocumentUploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    public void validateFile(MultipartFile file) {
        // 1. Check file presence
        if (file == null || file.isEmpty()) {
            throw new DocumentUploadException(
                "File is null or empty",
                "FILE_EMPTY"
            );
        }

        // 2. Check original filename
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new DocumentUploadException(
                "Original filename is missing",
                "FILENAME_MISSING"
            );
        }

        // 3. Validate file size
        if (file.getSize() > uploadProperties.maxFileSize()) {
            throw new DocumentUploadException(
                String.format("File size %d exceeds maximum allowed %d",
                    file.getSize(), uploadProperties.maxFileSize()),
                "FILE_TOO_LARGE"
            );
        }

        // 4. Extract and validate extension (includes validation and lowercase conversion)
        extractFileExtension(originalFilename);

        // 5. Normalize and validate filename
        String normalizedFilename = normalizeFilename(originalFilename);
        if (normalizedFilename.length() > uploadProperties.maxFilenameLength()) {
            throw new DocumentUploadException(
                String.format("Filename length exceeds maximum %d",
                    uploadProperties.maxFilenameLength()),
                "FILENAME_TOO_LONG"
            );
        }

        log.debug("File validation passed: {}", originalFilename);
    }

    public String extractFileExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == filename.length() - 1) {
            throw new DocumentUploadException(
                "Invalid file extension",
                "INVALID_EXTENSION"
            );
        }

        String fileExtension = filename.substring(dotIndex + 1).toLowerCase();

        if (fileExtension.length() > uploadProperties.maxExtensionLength()) {
            throw new DocumentUploadException(
                String.format("Extension length exceeds maximum %d",
                    uploadProperties.maxExtensionLength()),
                "EXTENSION_TOO_LONG"
            );
        }

        if (!uploadProperties.supportedExtensions().contains(fileExtension)) {
            throw new DocumentUploadException(
                String.format("Unsupported file type: %s", fileExtension),
                "UNSUPPORTED_FILE_TYPE"
            );
        }

        return fileExtension;
    }

    public String normalizeFilename(String filename) {
        // Clean path and extract only the filename (prevent path traversal)
        String normalizedFileName = StringUtils.cleanPath(filename.trim());
        String fileName = normalizedFileName.substring(normalizedFileName.lastIndexOf('/') + 1);

        // Remove leading and trailing spaces and dots
        fileName = fileName.trim().replaceAll("^\\.+|\\.+$", "");

        // Extract filename without extension
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            return fileName.substring(0, lastDot);
        }
        return fileName;
    }
}