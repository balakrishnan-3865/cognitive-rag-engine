package com.skyshift.cognitiveragengine.ingestion.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 7: the SQL-level half of the atomic cutover (Section 2/4) — retire the previous current
 * chunk set, promote the new run's shadow rows, and clean up shadow rows for a failed run.
 * {@link com.skyshift.cognitiveragengine.ingestion.service.DocumentChunkBatchServiceTest} covers
 * the orchestration/event-publishing side with mocks; this covers what the SQL actually does.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentChunkCutoverIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentChunkMapper documentChunkMapper;

    private Long createDocument(String objectKey) {
        jdbcTemplate.update(
            "insert into documents (group_id, uploaded_user_id, file_name, file_extension, file_size, storage_bucket, storage_object_key) " +
                "values (1, 1, 'cutover-test.pdf', 'pdf', 100, 'bucket', ?)",
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
    void cutover_retiresPriorCurrentRows_andPromotesNewRunRows() {
        Long documentId = createDocument("cutover-happy.pdf");
        Long oldRunId = createRun(documentId, "CUTOVER_COMPLETE");
        Long newRunId = createRun(documentId, "STREAMING");

        jdbcTemplate.update(
            "insert into document_chunks (group_id, document_id, chunk_number, chunk_text, ingestion_run_id, is_current) " +
                "values (1, ?, 1, 'old-current', ?, true)",
            documentId, oldRunId);
        jdbcTemplate.update(
            "insert into document_chunks (group_id, document_id, chunk_number, chunk_text, ingestion_run_id, is_current) " +
                "values (1, ?, 1, 'new-shadow', ?, false)",
            documentId, newRunId);

        int retired = documentChunkMapper.retireCurrentChunks(documentId, 1L);
        int promoted = documentChunkMapper.promoteRunChunks(newRunId);

        assertEquals(1, retired);
        assertEquals(1, promoted);

        Boolean oldIsCurrent = jdbcTemplate.queryForObject(
            "select is_current from document_chunks where ingestion_run_id = ?", Boolean.class, oldRunId);
        Boolean newIsCurrent = jdbcTemplate.queryForObject(
            "select is_current from document_chunks where ingestion_run_id = ?", Boolean.class, newRunId);

        assertEquals(false, oldIsCurrent);
        assertEquals(true, newIsCurrent);
    }

    @Test
    void deleteByIngestionRunId_removesOnlyThatRunsShadowRows_leavesCurrentRowsUntouched() {
        Long documentId = createDocument("cutover-cleanup.pdf");
        Long currentRunId = createRun(documentId, "CUTOVER_COMPLETE");
        Long failedRunId = createRun(documentId, "STREAMING");

        jdbcTemplate.update(
            "insert into document_chunks (group_id, document_id, chunk_number, chunk_text, ingestion_run_id, is_current) " +
                "values (1, ?, 1, 'stays-current', ?, true)",
            documentId, currentRunId);
        jdbcTemplate.update(
            "insert into document_chunks (group_id, document_id, chunk_number, chunk_text, ingestion_run_id, is_current) " +
                "values (1, ?, 1, 'orphaned-shadow', ?, false)",
            documentId, failedRunId);

        int deleted = documentChunkMapper.deleteByIngestionRunId(failedRunId);
        assertEquals(1, deleted);

        Integer remainingForFailedRun = jdbcTemplate.queryForObject(
            "select count(*) from document_chunks where ingestion_run_id = ?", Integer.class, failedRunId);
        Integer remainingForCurrentRun = jdbcTemplate.queryForObject(
            "select count(*) from document_chunks where ingestion_run_id = ?", Integer.class, currentRunId);

        assertEquals(0, remainingForFailedRun);
        assertEquals(1, remainingForCurrentRun);
    }
}
