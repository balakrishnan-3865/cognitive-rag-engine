package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentIngestionRunMapper;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DocumentStatus;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5: real hosted LlamaParse API call through the full {@link ParseAndChunkService} path
 * (Section "Test Inventory" in 03-plan.md). Opt-in — only runs when {@code LLAMA_CLOUD_API_KEY}
 * is present in the environment, since it performs a real network call and costs real API quota.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "parser.strategy=llama")
@EnabledIfEnvironmentVariable(named = "LLAMA_CLOUD_API_KEY", matches = ".+")
class ParseAndChunkServiceLlamaE2ETest {

    private static final Long GROUP_ID = 1L;
    private static final String SAMPLE_PDF = "src/test/resources/samples/sample-2page.pdf";

    @Autowired
    private ParseAndChunkService parseAndChunkService;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private ObjectStorageService objectStorageService;

    @Autowired
    private DocumentChunkMapper documentChunkMapper;

    @Autowired
    private DocumentIngestionRunMapper documentIngestionRunMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long documentId;
    private String objectKey;

    @AfterEach
    void cleanUp() {
        if (documentId != null) {
            documentChunkMapper.deleteByDocumentIdAndGroupId(documentId, GROUP_ID);
            jdbcTemplate.update("delete from document_ingestion_runs where document_id = ?", documentId);
            jdbcTemplate.update("delete from documents where id = ?", documentId);
        }
        if (objectKey != null) {
            objectStorageService.deleteObject(objectStorageService.getDefaultBucket(), objectKey);
        }
    }

    @Test
    void parseAndChunkDocument_realLlamaParseApi_producesReadyDocumentWithLlamaChunks() throws Exception {
        byte[] fileBytes = Files.readAllBytes(Path.of(SAMPLE_PDF));
        objectKey = "e2e/llamaparse/" + System.currentTimeMillis() + "-sample-2page.pdf";

        try (InputStream upload = new ByteArrayInputStream(fileBytes)) {
            objectStorageService.uploadObject(
                objectStorageService.getDefaultBucket(), objectKey, upload, fileBytes.length);
        }

        LocalDateTime now = LocalDateTime.now();
        DocumentEntity doc = DocumentEntity.builder()
            .groupId(GROUP_ID)
            .uploadedUserId(1L)
            .fileName("sample-2page.pdf")
            .fileExtension("pdf")
            .fileSize((long) fileBytes.length)
            .storageBucket(objectStorageService.getDefaultBucket())
            .storageObjectKey(objectKey)
            .status(DocumentStatus.PENDING.name())
            .deleted(false)
            .versionNumber(1)
            .isCurrentVersion(true)
            .uploadedAt(now)
            .createdAt(now)
            .updatedAt(now)
            .build();
        documentMapper.insert(doc);
        documentId = doc.getId();

        // parseAndChunkDocument's own scope ends at cutover (shadow-insert -> promote -> retire),
        // which is synchronous and completes before this call returns. The document's transition
        // to READY happens afterwards, asynchronously, via DocumentChunksCreatedEvent ->
        // ChunkVectorIngestionOrchestrator (embedding + ES indexing) — a downstream pipeline this
        // strategy-pattern refactor deliberately leaves untouched (03-plan.md: "VectorIngestionService,
        // ..., downstream QA/retrieval — untouched, zero diff") and whose success depends on local
        // infra (Ollama embedding model / pgvector schema dimension match) outside this feature's
        // blast radius. Assert on the strategy's own synchronous output instead.
        parseAndChunkService.parseAndChunkDocument(documentId, GROUP_ID);

        List<DocumentChunkEntity> chunks =
            documentChunkMapper.selectByDocumentIdAndGroupId(documentId, GROUP_ID);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getIsCurrent()).isTrue();
            // jsonb round-trips through Postgres's own canonical text form (space after ':'),
            // not the compact form ChunkMetadata.toJson() originally wrote.
            assertThat(chunk.getMetadataJson()).contains("\"chunkStrategy\": \"llama-structural-v1\"");
        });

        Integer runCount = jdbcTemplate.queryForObject(
            "select count(*) from document_ingestion_runs where document_id = ?", Integer.class, documentId);
        assertThat(runCount).isEqualTo(1);
    }
}
