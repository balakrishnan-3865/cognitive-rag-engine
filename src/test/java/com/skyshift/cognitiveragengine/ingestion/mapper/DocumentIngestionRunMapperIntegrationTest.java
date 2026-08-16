package com.skyshift.cognitiveragengine.ingestion.mapper;

import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentIngestionRunEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.IngestionRunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase 1 lifecycle tests for document_ingestion_runs / DocumentIngestionRunMapper
 * (see V20260817120000__add_docling_ingestion_runs.sql).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentIngestionRunMapperIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentIngestionRunMapper documentIngestionRunMapper;

    private Long createDocument(String objectKey) {
        jdbcTemplate.update(
            "insert into documents (group_id, uploaded_user_id, file_name, file_extension, file_size, storage_bucket, storage_object_key) " +
                "values (1, 1, 'lifecycle-test.pdf', 'pdf', 100, 'bucket', ?)",
            objectKey);
        return jdbcTemplate.queryForObject(
            "select id from documents where storage_object_key = ?", Long.class, objectKey);
    }

    @Test
    void insert_onRunStart_persistsStreamingRunWithGeneratedId() {
        Long documentId = createDocument("lifecycle-test-start.pdf");

        DocumentIngestionRunEntity run = DocumentIngestionRunEntity.builder()
            .documentId(documentId)
            .status(IngestionRunStatus.STREAMING.name())
            .build();

        documentIngestionRunMapper.insert(run);

        assertNotNull(run.getId());

        DocumentIngestionRunEntity persisted = documentIngestionRunMapper.selectById(run.getId());
        assertEquals(IngestionRunStatus.STREAMING.name(), persisted.getStatus());
        assertNull(persisted.getCompletedAt());
    }

    @Test
    void updateStatus_transitionsRunAndStampsCompletedAtOnTerminalStatus() {
        Long documentId = createDocument("lifecycle-test-transition.pdf");
        DocumentIngestionRunEntity run = DocumentIngestionRunEntity.builder()
            .documentId(documentId)
            .status(IngestionRunStatus.STREAMING.name())
            .build();
        documentIngestionRunMapper.insert(run);

        documentIngestionRunMapper.updateStatus(run.getId(), IngestionRunStatus.CUTOVER_COMPLETE.name());

        DocumentIngestionRunEntity persisted = documentIngestionRunMapper.selectById(run.getId());
        assertEquals(IngestionRunStatus.CUTOVER_COMPLETE.name(), persisted.getStatus());
        assertNotNull(persisted.getCompletedAt());
    }

    @Test
    void updateStatus_toFailed_rowSurvivesWithCompletedAtSet() {
        Long documentId = createDocument("lifecycle-test-failed.pdf");
        DocumentIngestionRunEntity run = DocumentIngestionRunEntity.builder()
            .documentId(documentId)
            .status(IngestionRunStatus.STREAMING.name())
            .build();
        documentIngestionRunMapper.insert(run);

        documentIngestionRunMapper.updateStatus(run.getId(), IngestionRunStatus.FAILED.name());

        DocumentIngestionRunEntity persisted = documentIngestionRunMapper.selectById(run.getId());
        assertEquals(IngestionRunStatus.FAILED.name(), persisted.getStatus());
        assertNotNull(persisted.getCompletedAt());
    }

    @Test
    void deleteById_afterCutoverComplete_removesRunRow() {
        Long documentId = createDocument("lifecycle-test-cleanup.pdf");
        DocumentIngestionRunEntity run = DocumentIngestionRunEntity.builder()
            .documentId(documentId)
            .status(IngestionRunStatus.STREAMING.name())
            .build();
        documentIngestionRunMapper.insert(run);
        documentIngestionRunMapper.updateStatus(run.getId(), IngestionRunStatus.CUTOVER_COMPLETE.name());

        documentIngestionRunMapper.deleteById(run.getId());

        assertNull(documentIngestionRunMapper.selectById(run.getId()));
    }
}
