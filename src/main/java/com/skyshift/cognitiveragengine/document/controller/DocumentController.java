package com.skyshift.cognitiveragengine.document.controller;

import com.skyshift.cognitiveragengine.document.model.dto.DocumentCreatedResponse;
import com.skyshift.cognitiveragengine.document.model.dto.DocumentUploadRequest;
import com.skyshift.cognitiveragengine.document.service.DocumentService;
import com.skyshift.cognitiveragengine.user.model.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public ResponseEntity<DocumentCreatedResponse> uploadDocument(
        @ModelAttribute DocumentUploadRequest uploadRequest,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        log.info("Document upload request received: groupId={}", principal.getGroupId());

        Long documentId = documentService.uploadDocument(
            uploadRequest.file(),
            principal.getGroupId(),
            principal.getId()
        );

        log.info("Document uploaded successfully: documentId={}, groupId={}",
            documentId, principal.getGroupId());

        return ResponseEntity.status(HttpStatus.CREATED).body(new DocumentCreatedResponse(documentId));
    }

    @PostMapping("/{documentId}/versions")
    public ResponseEntity<DocumentCreatedResponse> uploadNewVersion(
        @PathVariable Long documentId,
        @ModelAttribute DocumentUploadRequest uploadRequest,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        log.info("New document version upload request received: documentId={}, groupId={}",
            documentId, principal.getGroupId());

        Long newDocumentId = documentService.uploadNewVersion(
            documentId,
            principal.getGroupId(),
            uploadRequest.file(),
            principal.getId()
        );

        log.info("New document version uploaded successfully: documentId={}, parentDocumentId={}, groupId={}",
            newDocumentId, documentId, principal.getGroupId());

        return ResponseEntity.status(HttpStatus.CREATED).body(new DocumentCreatedResponse(newDocumentId));
    }

    @PostMapping("/{documentId}/versions/{targetVersionId}/revert")
    public ResponseEntity<Void> revertToVersion(
        @PathVariable Long documentId,
        @PathVariable Long targetVersionId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        log.info("Revert request received: documentId={}, targetVersionId={}, groupId={}",
            documentId, targetVersionId, principal.getGroupId());

        documentService.revertToVersion(documentId, targetVersionId, principal.getGroupId());

        log.info("Reverted to previous version successfully: documentId={}, targetVersionId={}, groupId={}",
            documentId, targetVersionId, principal.getGroupId());

        return ResponseEntity.noContent().build();
    }
}