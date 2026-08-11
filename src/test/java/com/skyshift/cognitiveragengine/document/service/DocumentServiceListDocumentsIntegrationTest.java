package com.skyshift.cognitiveragengine.document.service;

import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.document.model.dto.DocumentSummaryResponse;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Integration tests for GET /api/v1/documents' backing service method: a flat, per-lineage
 * document list scoped to both groupId and uploadedUserId, sourced from the real documents
 * table (mirrors DocumentServiceVersioningIntegrationTest's approach).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentServiceListDocumentsIntegrationTest {

    private static final Long GROUP_ID = 999_101L;
    private static final Long USER_ID = 101L;
    private static final Long OTHER_USER_ID = 102L;
    private static final Long OTHER_GROUP_ID = 999_102L;

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
    void listDocuments_returnsOnlyDocumentsForMatchingGroupAndUser() {
        documentService.uploadDocument(pdfFile("mine-1.pdf"), GROUP_ID, USER_ID);
        documentService.uploadDocument(pdfFile("mine-2.pdf"), GROUP_ID, USER_ID);
        documentService.uploadDocument(pdfFile("same-group-other-user.pdf"), GROUP_ID, OTHER_USER_ID);
        documentService.uploadDocument(pdfFile("other-group-same-user.pdf"), OTHER_GROUP_ID, USER_ID);

        List<DocumentSummaryResponse> result = documentService.listDocuments(GROUP_ID, USER_ID);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(d -> d.title().startsWith("mine-")));
    }

    @Test
    void listDocuments_returnsEmptyListWhenCallerHasNoDocuments() {
        List<DocumentSummaryResponse> result = documentService.listDocuments(GROUP_ID, USER_ID);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void listDocuments_excludesSupersededVersions_showsOnlyCurrentVersionPerLineage() {
        Long v1 = documentService.uploadDocument(pdfFile("lineage.pdf"), GROUP_ID, USER_ID);
        documentMapper.updateStatus(v1, "READY");
        Long v2 = documentService.uploadNewVersion(v1, GROUP_ID, pdfFile("lineage-v2.pdf"), USER_ID);
        documentMapper.updateStatus(v2, "READY");
        // Simulate the READY-promotion hook that normally flips current after ingestion succeeds.
        documentMapper.flipCurrentVersion(v1, v2);

        List<DocumentSummaryResponse> result = documentService.listDocuments(GROUP_ID, USER_ID);

        assertEquals(1, result.size());
        assertEquals(v2, result.getFirst().id());
        assertEquals("v2", result.getFirst().latestVersionLabel());
    }

    @Test
    void listDocuments_excludesSoftDeletedDocuments() {
        Long documentId = documentService.uploadDocument(pdfFile("to-delete.pdf"), GROUP_ID, USER_ID);
        jdbcTemplate.update("update documents set deleted = true where id = ?", documentId);

        List<DocumentSummaryResponse> result = documentService.listDocuments(GROUP_ID, USER_ID);

        assertTrue(result.isEmpty());
    }

    @Test
    void listDocuments_mapsFieldsFromEntity() {
        Long documentId = documentService.uploadDocument(pdfFile("report.pdf"), GROUP_ID, USER_ID);

        DocumentSummaryResponse summary = documentService.listDocuments(GROUP_ID, USER_ID).getFirst();

        assertEquals(documentId, summary.id());
        assertEquals("report", summary.title());
        assertEquals("v1", summary.latestVersionLabel());
        assertEquals("PENDING", summary.status());
        assertNotNull(summary.updatedAt());
    }

    @Test
    void listDocuments_ordersByUpdatedAtDescending() {
        Long older = documentService.uploadDocument(pdfFile("older.pdf"), GROUP_ID, USER_ID);
        Long newer = documentService.uploadDocument(pdfFile("newer.pdf"), GROUP_ID, USER_ID);
        jdbcTemplate.update("update documents set updated_at = ? where id = ?",
            LocalDateTime.now().minusDays(1), older);
        jdbcTemplate.update("update documents set updated_at = ? where id = ?",
            LocalDateTime.now(), newer);

        List<DocumentSummaryResponse> result = documentService.listDocuments(GROUP_ID, USER_ID);

        assertEquals(newer, result.get(0).id());
        assertEquals(older, result.get(1).id());
    }

    private MultipartFile pdfFile(String filename) {
        return new MockMultipartFile("file", filename, "application/pdf", "dummy pdf content".getBytes());
    }
}
