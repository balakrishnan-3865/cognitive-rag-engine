package com.skyshift.cognitiveragengine.document.service;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.common.exception.StorageException;
import com.skyshift.cognitiveragengine.document.config.DocumentUploadProperties;
import com.skyshift.cognitiveragengine.document.event.DocumentUploadedEvent;
import com.skyshift.cognitiveragengine.document.exception.DocumentUploadException;
import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class DocumentService {
    private final DocumentMapper documentMapper;
    private final ObjectStorageService objectStorageService;
    private final DocumentUploadProperties uploadProperties;
    private final FileValidator fileValidator;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentService(
        DocumentMapper documentMapper,
        ObjectStorageService objectStorageService,
        DocumentUploadProperties uploadProperties,
        ApplicationEventPublisher eventPublisher
    ) {
        this.documentMapper = documentMapper;
        this.objectStorageService = objectStorageService;
        this.uploadProperties = uploadProperties;
        this.fileValidator = new FileValidator(uploadProperties);
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Long uploadDocument(
        MultipartFile file,
        Long groupId,
        Long uploadedUserId
    ) {
        // Step 1: Validate file
        fileValidator.validateFile(file);

        String fileExtension = fileValidator.extractFileExtension(file.getOriginalFilename());
        String normalizedFilename = fileValidator.normalizeFilename(file.getOriginalFilename());
        String bucket = objectStorageService.getDefaultBucket();
        String objectKey = generateObjectKey(fileExtension, uploadedUserId, groupId);

        log.debug("Generated objectKey: {}", objectKey);

        // Step 2: Upload to MinIO
        uploadToMinIO(file, bucket, objectKey);

        // Step 3: Create and persist document with compensating transaction
        return createAndPersistDocument(
            normalizedFilename,
            fileExtension,
            file.getSize(),
            bucket,
            objectKey,
            groupId,
            uploadedUserId
        );
    }

    private void uploadToMinIO(MultipartFile file, String bucket, String objectKey) {
        try {
            log.info("Uploading file to MinIO: bucket={}, objectKey={}", bucket, objectKey);
            objectStorageService.uploadObject(
                bucket,
                objectKey,
                file.getInputStream(),
                file.getSize()
            );
            log.debug("File successfully uploaded to MinIO: bucket={}, objectKey={}", bucket, objectKey);
        } catch (IOException e) {
            log.error("IO error while reading file content", e);
            throw new DocumentUploadException(
                "Failed to read file content: " + e.getMessage(),
                "FILE_READ_ERROR",
                e
            );
        } catch (StorageException e) {
            log.error("Storage service error during upload: bucket={}, objectKey={}", bucket, objectKey, e);
            throw new DocumentUploadException(
                "Failed to upload file to storage: " + e.getMessage(),
                "STORAGE_ERROR",
                e
            );
        }
    }


    private Long createAndPersistDocument(
        String normalizedFilename,
        String fileExtension,
        Long fileSize,
        String bucket,
        String objectKey,
        Long groupId,
        Long uploadedUserId
    ) {
        try {
            DocumentEntity documentEntity = DocumentEntity.builder()
                .groupId(groupId)
                .uploadedUserId(uploadedUserId)
                .fileName(normalizedFilename)
                .fileExtension(fileExtension)
                .fileSize(fileSize)
                .storageBucket(bucket)
                .storageObjectKey(objectKey)
                .status("PENDING")
                .deleted(false)
                .uploadedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

            int insertResult = documentMapper.insert(documentEntity);
            if (insertResult != 1) {
                throw new DocumentUploadException(
                    "Failed to insert document into database",
                    "DB_INSERT_FAILED"
                );
            }

            Long documentId = documentEntity.getId();
            log.info("Document persisted to database: documentId={}, groupId={}", documentId, groupId);

            // Publish event
            publishDocumentUploadedEvent(documentId, groupId);

            return documentId;

        } catch (DocumentUploadException e) {
            log.error("Compensating transaction: deleting object from MinIO due to DB error", e);
            rollbackMinIOUpload(bucket, objectKey);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during document persistence: {}", e.getMessage(), e);
            log.error("Compensating transaction: deleting object from MinIO");
            rollbackMinIOUpload(bucket, objectKey);
            throw new DocumentUploadException(
                "Unexpected error during document creation: " + e.getMessage(),
                "DOCUMENT_CREATION_FAILED",
                e
            );
        }
    }

    private void publishDocumentUploadedEvent(Long documentId, Long groupId) {
        try {
            eventPublisher.publishEvent(new DocumentUploadedEvent(documentId, groupId));
            log.debug("DocumentUploadedEvent published: documentId={}, groupId={}", documentId, groupId);
        } catch (Exception e) {
            log.error("Failed to publish DocumentUploadedEvent for documentId={}", documentId, e);
            throw new BusinessException("Document Event publishing failed.");
        }
    }

    private void rollbackMinIOUpload(String bucket, String objectKey) {
        try {
            objectStorageService.deleteObject(bucket, objectKey);
            log.info("Compensating rollback successful: deleted bucket={}, objectKey={}", bucket, objectKey);
        } catch (Exception deleteError) {
            log.error("Failed to rollback MinIO object deletion: bucket={}, objectKey={}", bucket, objectKey, deleteError);
        }
    }

    private String generateObjectKey(String fileExtension, Long userId, Long groupId) {
        String fileId = UUID.randomUUID().toString().replace("-", "");
        return "groups/%d/users/%d/%s.%s".formatted(groupId, userId, fileId, fileExtension);
    }
}