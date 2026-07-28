package com.skyshift.cognitiveragengine.document.controller;

import com.skyshift.cognitiveragengine.common.model.ApiResponse;
import com.skyshift.cognitiveragengine.document.model.dto.DocumentUploadRequest;
import com.skyshift.cognitiveragengine.document.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
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
    public ResponseEntity<ApiResponse<Long>> uploadDocument(
        @ModelAttribute DocumentUploadRequest uploadRequest,
        HttpServletRequest request
    ) {
        log.info("Document upload request received: groupId={}", uploadRequest.groupId());

        // TODO: Extract userId from HttpServletRequest via SecurityContext
        Long uploadedUserId = 1L; // Placeholder

        Long documentId = documentService.uploadDocument(
            uploadRequest.file(),
            uploadRequest.groupId(),
            uploadedUserId
        );

        log.info("Document uploaded successfully: documentId={}, groupId={}",
            documentId, uploadRequest.groupId());

        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse.ok(documentId, "Document uploaded successfully")
        );
    }
}