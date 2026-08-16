package com.skyshift.cognitiveragengine.ingestion.mapper;

import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1 schema tests for the ingestion_run_id / is_current columns added to document_chunks
 * (see V20260817120000__add_docling_ingestion_runs.sql).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentChunkSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentChunkMapper documentChunkMapper;

    private Long createDocument(String objectKey) {
        jdbcTemplate.update(
            "insert into documents (group_id, uploaded_user_id, file_name, file_extension, file_size, storage_bucket, storage_object_key) " +
                "values (1, 1, 'schema-test.pdf', 'pdf', 100, 'bucket', ?)",
            objectKey);
        return jdbcTemplate.queryForObject(
            "select id from documents where storage_object_key = ?", Long.class, objectKey);
    }

    private Long createRun(Long documentId, String status) {
        jdbcTemplate.update(
            "insert into document_ingestion_runs (document_id, status) values (?, ?)",
            documentId, status);
        return jdbcTemplate.queryForObject(
            "select max(id) from document_ingestion_runs where document_id = ?", Long.class, documentId);
    }

    @Test
    void documentChunks_uniqueConstraint_rejectsDuplicateRunAndChunkNumber() {
        Long documentId = createDocument("schema-test-unique.pdf");
        Long runId = createRun(documentId, "STREAMING");

        jdbcTemplate.update(
            "insert into document_chunks (group_id, document_id, chunk_number, chunk_text, ingestion_run_id, is_current) " +
                "values (1, ?, 1, 'first', ?, false)",
            documentId, runId);

        assertThrows(DataIntegrityViolationException.class, () ->
            jdbcTemplate.update(
                "insert into document_chunks (group_id, document_id, chunk_number, chunk_text, ingestion_run_id, is_current) " +
                    "values (1, ?, 1, 'duplicate', ?, false)",
                documentId, runId));
    }

    @Test
    void batchInsertChunks_onConflict_isSilentNoOpNotAnError() {
        Long documentId = createDocument("schema-test-conflict.pdf");
        Long runId = createRun(documentId, "STREAMING");

        LocalDateTime now = LocalDateTime.now();

        DocumentChunkEntity chunk = DocumentChunkEntity.builder()
            .groupId(1L)
            .documentId(documentId)
            .chunkNumber(1)
            .chunkText("first")
            .ingestionRunId(runId)
            .isCurrent(false)
            .createdAt(now)
            .updatedAt(now)
            .build();

        documentChunkMapper.batchInsertChunks(List.of(chunk));

        DocumentChunkEntity duplicateChunk = DocumentChunkEntity.builder()
            .groupId(1L)
            .documentId(documentId)
            .chunkNumber(1)
            .chunkText("duplicate-should-be-ignored")
            .ingestionRunId(runId)
            .isCurrent(false)
            .createdAt(now)
            .updatedAt(now)
            .build();

        // Should not throw — ON CONFLICT DO NOTHING makes the retry a silent no-op.
        documentChunkMapper.batchInsertChunks(List.of(duplicateChunk));

        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from document_chunks where document_id = ? and ingestion_run_id = ?",
            Integer.class, documentId, runId);
        assertEquals(1, count);

        String persistedText = jdbcTemplate.queryForObject(
            "select chunk_text from document_chunks where document_id = ? and ingestion_run_id = ?",
            String.class, documentId, runId);
        assertEquals("first", persistedText);
    }

    @Test
    void selectByDocumentIdAndGroupId_withShadowRowsPresent_returnsOnlyIsCurrentRows() {
        Long documentId = createDocument("schema-test-shadow.pdf");
        Long currentRunId = createRun(documentId, "CUTOVER_COMPLETE");
        Long shadowRunId = createRun(documentId, "STREAMING");

        jdbcTemplate.update(
            "insert into document_chunks (group_id, document_id, chunk_number, chunk_text, ingestion_run_id, is_current) " +
                "values (1, ?, 1, 'current-chunk', ?, true)",
            documentId, currentRunId);
        jdbcTemplate.update(
            "insert into document_chunks (group_id, document_id, chunk_number, chunk_text, ingestion_run_id, is_current) " +
                "values (1, ?, 1, 'shadow-chunk', ?, false)",
            documentId, shadowRunId);

        List<DocumentChunkEntity> result = documentChunkMapper.selectByDocumentIdAndGroupId(documentId, 1L);

        assertEquals(1, result.size());
        assertEquals("current-chunk", result.get(0).getChunkText());
    }
}
