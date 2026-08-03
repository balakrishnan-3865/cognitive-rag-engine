package com.skyshift.cognitiveragengine.document.service;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.common.exception.StorageException;
import com.skyshift.cognitiveragengine.document.config.DocumentUploadProperties;
import com.skyshift.cognitiveragengine.document.event.DocumentUploadedEvent;
import com.skyshift.cognitiveragengine.document.exception.DocumentUploadException;
import com.skyshift.cognitiveragengine.document.exception.DocumentVersionConflictException;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class DocumentService {
    private static final Set<String> TERMINAL_STATUSES = Set.of("READY", "FAILED", "NO_CHUNKS_FOUND");

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
            uploadedUserId,
            null,
            1,
            true
        );
    }

    @Transactional
    public Long uploadNewVersion(
        Long documentId,
        Long groupId,
        MultipartFile file,
        Long uploadedUserId
    ) {
        DocumentEntity parent = documentMapper.selectByIdAndGroupId(documentId, groupId);
        if (parent == null) {
            throw new BusinessException("Document not found: documentId=" + documentId);
        }
        if (!TERMINAL_STATUSES.contains(parent.getStatus())) {
            throw new DocumentVersionConflictException(
                "Cannot upload a new version while the current version is still processing: documentId=" + documentId
            );
        }
        if (!Boolean.TRUE.equals(parent.getIsCurrentVersion())) {
            throw new DocumentVersionConflictException(
                "Cannot version off a superseded document; version off the current version instead: documentId=" + documentId
            );
        }

        fileValidator.validateFile(file);

        String fileExtension = fileValidator.extractFileExtension(file.getOriginalFilename());
        String normalizedFilename = fileValidator.normalizeFilename(file.getOriginalFilename());
        String bucket = objectStorageService.getDefaultBucket();
        String objectKey = generateObjectKey(fileExtension, uploadedUserId, groupId);

        log.debug("Generated objectKey: {}", objectKey);

        uploadToMinIO(file, bucket, objectKey);

        Long rootDocumentId = parent.getRootDocumentId() != null ? parent.getRootDocumentId() : parent.getId();
        int nextVersionNumber = parent.getVersionNumber() + 1;

        return createAndPersistDocument(
            normalizedFilename,
            fileExtension,
            file.getSize(),
            bucket,
            objectKey,
            groupId,
            uploadedUserId,
            rootDocumentId,
            nextVersionNumber,
            false
        );
    }

    /**
     * Resolves the set of document ids that are searchable for a group: the current version
     * of each document lineage, only once it has finished ingesting (status READY).
     */
    public List<Long> findCurrentReadyDocumentIds(Long groupId) {
        return documentMapper.findCurrentReadyDocumentIds(groupId);
    }

    @Transactional
    public void revertToVersion(Long documentId, Long targetVersionId, Long groupId) {
        DocumentEntity current = documentMapper.selectByIdAndGroupId(documentId, groupId);
        DocumentEntity target = documentMapper.selectByIdAndGroupId(targetVersionId, groupId);
        if (current == null || target == null) {
            throw new BusinessException(
                "Document not found: documentId=" + documentId + ", targetVersionId=" + targetVersionId);
        }

        Long currentRoot = current.getRootDocumentId() != null ? current.getRootDocumentId() : current.getId();
        Long targetRoot = target.getRootDocumentId() != null ? target.getRootDocumentId() : target.getId();
        if (!currentRoot.equals(targetRoot)) {
            throw new DocumentVersionConflictException(
                "Target version does not belong to the same document lineage: targetVersionId=" + targetVersionId);
        }
        if (!"READY".equals(target.getStatus())) {
            throw new DocumentVersionConflictException(
                "Cannot revert to a version that is not READY: targetVersionId=" + targetVersionId);
        }
        if (!Boolean.TRUE.equals(current.getIsCurrentVersion())) {
            throw new DocumentVersionConflictException(
                "documentId is not the current version: documentId=" + documentId);
        }
        if (Boolean.TRUE.equals(target.getIsCurrentVersion())) {
            throw new DocumentVersionConflictException(
                "Target version is already the current version: targetVersionId=" + targetVersionId);
        }

        int flipped = documentMapper.flipCurrentVersion(current.getId(), target.getId());
        if (flipped == 0) {
            throw new DocumentVersionConflictException(
                "Failed to revert: current version changed concurrently, please retry");
        }
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
        Long uploadedUserId,
        Long rootDocumentId,
        Integer versionNumber,
        Boolean isCurrentVersion
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
                .rootDocumentId(rootDocumentId)
                .versionNumber(versionNumber)
                .isCurrentVersion(isCurrentVersion)
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