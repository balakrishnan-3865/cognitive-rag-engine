package com.skyshift.cognitiveragengine.document.service;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Covers DocumentService.resolveSearchableDocumentIds - the single-document QA scoping lookup.
 * A null documentId preserves today's whole-group behavior; a non-null documentId must resolve
 * only when it is READY, the current version, and owned by the caller's group - otherwise it must
 * reject clearly (BusinessException -> 400, see GlobalExceptionHandler) rather than silently
 * falling back to whole-group search. Mirrors DocumentServiceListDocumentsIntegrationTest's
 * approach: real documents table via the test DB, ObjectStorageService mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentServiceDocumentScopingIntegrationTest {

    private static final Long GROUP_ID = 999_201L;
    private static final Long OTHER_GROUP_ID = 999_202L;
    private static final Long USER_ID = 201L;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private com.skyshift.cognitiveragengine.storage.service.ObjectStorageService objectStorageService;

    @BeforeEach
    void setUp() {
        when(objectStorageService.getDefaultBucket()).thenReturn("test-bucket");
    }

    @Test
    void resolveSearchableDocumentIds_nullDocumentId_returnsAllCurrentReadyDocumentIdsForGroup() {
        Long ready = documentService.uploadDocument(pdfFile("ready.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(ready, "READY");
        documentService.uploadDocument(pdfFile("still-processing.pdf"), GROUP_ID, USER_ID);

        List<Long> resolved = documentService.resolveSearchableDocumentIds(GROUP_ID, null);

        assertEquals(List.of(ready), resolved);
    }

    @Test
    void resolveSearchableDocumentIds_readyDocumentOwnedByGroup_returnsSingletonList() {
        Long documentId = documentService.uploadDocument(pdfFile("mine.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(documentId, "READY");

        List<Long> resolved = documentService.resolveSearchableDocumentIds(GROUP_ID, documentId);

        assertEquals(List.of(documentId), resolved);
    }

    @Test
    void resolveSearchableDocumentIds_unknownDocumentId_throwsBusinessException() {
        assertThrows(BusinessException.class,
            () -> documentService.resolveSearchableDocumentIds(GROUP_ID, 424_242L));
    }

    @Test
    void resolveSearchableDocumentIds_documentBelongsToDifferentGroup_throwsBusinessException() {
        Long documentId = documentService.uploadDocument(pdfFile("other-tenant.pdf"), OTHER_GROUP_ID, USER_ID);
        documentMapper.updateStatus(documentId, "READY");

        assertThrows(BusinessException.class,
            () -> documentService.resolveSearchableDocumentIds(GROUP_ID, documentId));
    }

    @Test
    void resolveSearchableDocumentIds_documentStillProcessing_throwsBusinessException() {
        Long documentId = documentService.uploadDocument(pdfFile("pending.pdf"), GROUP_ID, USER_ID);

        assertThrows(BusinessException.class,
            () -> documentService.resolveSearchableDocumentIds(GROUP_ID, documentId));
    }

    @Test
    void resolveSearchableDocumentIds_supersededVersion_throwsBusinessException() {
        Long v1 = documentService.uploadDocument(pdfFile("lineage.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(v1, "READY");
        Long v2 = documentService.uploadNewVersion(v1, GROUP_ID, pdfFile("lineage-v2.pdf"), USER_ID);
        documentMapper.updateStatus(v2, "READY");
        documentMapper.flipCurrentVersion(v1, v2);

        assertThrows(BusinessException.class,
            () -> documentService.resolveSearchableDocumentIds(GROUP_ID, v1));
    }

    @Test
    void resolveSearchableDocumentIds_softDeletedDocument_throwsBusinessException() {
        Long documentId = documentService.uploadDocument(pdfFile("deleted.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(documentId, "READY");
        jdbcTemplate.update("update documents set deleted = true where id = ?", documentId);

        assertThrows(BusinessException.class,
            () -> documentService.resolveSearchableDocumentIds(GROUP_ID, documentId));
    }

    private MultipartFile pdfFile(String filename) {
        return new MockMultipartFile("file", filename, "application/pdf", "dummy pdf content".getBytes());
    }
}
