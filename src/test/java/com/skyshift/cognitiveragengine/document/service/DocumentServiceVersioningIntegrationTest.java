package com.skyshift.cognitiveragengine.document.service;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.document.exception.DocumentVersionConflictException;
import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Integration tests for document versioning (Phase 1 upload + Phase 3 revert) against the
 * real documents table. The test class runs each test in a rolled-back transaction so the
 * AFTER_COMMIT ingestion listener never fires (no real ingestion is triggered), and the DB
 * resets between tests automatically.
 */
@SpringBootTest
@Transactional
class DocumentServiceVersioningIntegrationTest {

    private static final Long GROUP_ID = 999_001L;
    private static final Long USER_ID = 1L;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentMapper documentMapper;

    @MockitoBean
    private ObjectStorageService objectStorageService;

    @BeforeEach
    void setUp() {
        when(objectStorageService.getDefaultBucket()).thenReturn("test-bucket");
    }

    @Test
    void uploadDocument_createsRootVersionMetadata() {
        Long documentId = documentService.uploadDocument(pdfFile("original.pdf"), GROUP_ID, USER_ID);

        DocumentEntity entity = documentMapper.selectById(documentId);

        assertNull(entity.getRootDocumentId());
        assertEquals(1, entity.getVersionNumber());
        assertTrue(entity.getIsCurrentVersion());
        assertEquals("PENDING", entity.getStatus());
    }

    @Test
    void uploadNewVersion_rejectsWhileParentIsProcessing() {
        Long parentId = documentService.uploadDocument(pdfFile("original.pdf"), GROUP_ID, USER_ID);
        // Freshly uploaded document is PENDING, a non-terminal status.

        assertThrows(DocumentVersionConflictException.class, () ->
            documentService.uploadNewVersion(parentId, GROUP_ID, pdfFile("v2.pdf"), USER_ID));
    }

    @Test
    void uploadNewVersion_rejectsWhenParentIsNotCurrentVersion() {
        Long parentId = documentService.uploadDocument(pdfFile("original.pdf"), GROUP_ID, USER_ID);
        Long otherId = documentService.uploadDocument(pdfFile("unrelated.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(parentId, "READY");
        // Force parent out of "current" without going through the normal READY-promotion hook.
        documentMapper.flipCurrentVersion(parentId, otherId);

        DocumentVersionConflictException exception = assertThrows(DocumentVersionConflictException.class, () ->
            documentService.uploadNewVersion(parentId, GROUP_ID, pdfFile("v2.pdf"), USER_ID));
        assertTrue(exception.getMessage().contains("superseded"));
    }

    @Test
    void uploadNewVersion_documentNotFound_throwsBusinessException() {
        assertThrows(BusinessException.class, () ->
            documentService.uploadNewVersion(-1L, GROUP_ID, pdfFile("v2.pdf"), USER_ID));
    }

    @Test
    void uploadNewVersion_succeedsAndDefersCurrentVersionPromotion() {
        Long parentId = documentService.uploadDocument(pdfFile("original.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(parentId, "READY");

        Long childId = documentService.uploadNewVersion(parentId, GROUP_ID, pdfFile("v2.pdf"), USER_ID);

        DocumentEntity child = documentMapper.selectById(childId);
        assertEquals(parentId, child.getRootDocumentId());
        assertEquals(2, child.getVersionNumber());
        assertFalse(child.getIsCurrentVersion());
        assertEquals("PENDING", child.getStatus());

        // Parent remains the searchable current version until the child actually reaches READY.
        DocumentEntity parent = documentMapper.selectById(parentId);
        assertTrue(parent.getIsCurrentVersion());
    }

    @Test
    void revertToVersion_flipsCurrentFlagBetweenVersions() {
        Long v1 = documentService.uploadDocument(pdfFile("v1.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(v1, "READY");
        Long v2 = documentService.uploadNewVersion(v1, GROUP_ID, pdfFile("v2.pdf"), USER_ID);
        documentMapper.updateStatus(v2, "READY");
        // Simulate the Phase 2 READY-promotion hook that normally runs after ingestion succeeds.
        documentMapper.flipCurrentVersion(v1, v2);

        documentService.revertToVersion(v2, v1, GROUP_ID);

        assertTrue(documentMapper.selectById(v1).getIsCurrentVersion());
        assertFalse(documentMapper.selectById(v2).getIsCurrentVersion());
    }

    @Test
    void revertToVersion_rejectsTargetThatIsNotReady() {
        Long v1 = documentService.uploadDocument(pdfFile("v1.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(v1, "READY");
        Long v2 = documentService.uploadNewVersion(v1, GROUP_ID, pdfFile("v2.pdf"), USER_ID);
        // v2 stays PENDING - never reached READY.

        DocumentVersionConflictException exception = assertThrows(DocumentVersionConflictException.class, () ->
            documentService.revertToVersion(v1, v2, GROUP_ID));
        assertTrue(exception.getMessage().contains("not READY"));
    }

    @Test
    void revertToVersion_rejectsDifferentLineage() {
        Long docA = documentService.uploadDocument(pdfFile("a.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(docA, "READY");
        Long docB = documentService.uploadDocument(pdfFile("b.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(docB, "READY");

        DocumentVersionConflictException exception = assertThrows(DocumentVersionConflictException.class, () ->
            documentService.revertToVersion(docA, docB, GROUP_ID));
        assertTrue(exception.getMessage().contains("lineage"));
    }

    @Test
    void revertToVersion_rejectsTargetThatIsAlreadyCurrent() {
        Long v1 = documentService.uploadDocument(pdfFile("v1.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(v1, "READY");

        DocumentVersionConflictException exception = assertThrows(DocumentVersionConflictException.class, () ->
            documentService.revertToVersion(v1, v1, GROUP_ID));
        assertTrue(exception.getMessage().contains("already the current version"));
    }

    private MultipartFile pdfFile(String filename) {
        return new MockMultipartFile("file", filename, "application/pdf", "dummy pdf content".getBytes());
    }
}
