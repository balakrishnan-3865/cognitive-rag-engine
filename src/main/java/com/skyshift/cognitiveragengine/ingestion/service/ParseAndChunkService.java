package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.ingestion.client.DoclingClient;
import com.skyshift.cognitiveragengine.ingestion.docling.DoclingChunkAssembler;
import com.skyshift.cognitiveragengine.ingestion.docling.DoclingDocumentParser;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentIngestionRunMapper;
import com.skyshift.cognitiveragengine.ingestion.model.dto.ChunkMetadata;
import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingItem;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentIngestionRunEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DocumentStatus;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingTaskStatus;
import com.skyshift.cognitiveragengine.ingestion.model.enums.IngestionRunStatus;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 7: the real Docling flow (Section 4's happy path, Section 5's failure path). Deliberately
 * NOT {@code @Transactional} — Section 2: no outer transaction wraps this method. Each shadow
 * batch flush and the final cutover are their own short, independently committing units; the
 * cutover transaction (in {@link DocumentChunkBatchService#cutover}) is the only place atomicity
 * is actually needed, since that's where visibility flips and the completion event publishes.
 */
@Slf4j
@Service
public class ParseAndChunkService {

    private final DocumentMapper documentMapper;
    private final ObjectStorageService objectStorageService;
    private final DoclingClient doclingClient;
    private final DocumentIngestionRunMapper documentIngestionRunMapper;
    private final DoclingDocumentParser doclingDocumentParser;
    private final DoclingChunkAssembler doclingChunkAssembler;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentChunkBatchService documentChunkBatchService;
    private final long pollIntervalMs;
    private final int pollMaxAttempts;

    public ParseAndChunkService(
            DocumentMapper documentMapper,
            ObjectStorageService objectStorageService,
            DoclingClient doclingClient,
            DocumentIngestionRunMapper documentIngestionRunMapper,
            DoclingDocumentParser doclingDocumentParser,
            DoclingChunkAssembler doclingChunkAssembler,
            DocumentChunkMapper documentChunkMapper,
            DocumentChunkBatchService documentChunkBatchService,
            @Value("${docling.poll-interval-ms:2000}") long pollIntervalMs,
            @Value("${docling.poll-max-attempts:150}") int pollMaxAttempts) {
        this.documentMapper = documentMapper;
        this.objectStorageService = objectStorageService;
        this.doclingClient = doclingClient;
        this.documentIngestionRunMapper = documentIngestionRunMapper;
        this.doclingDocumentParser = doclingDocumentParser;
        this.doclingChunkAssembler = doclingChunkAssembler;
        this.documentChunkMapper = documentChunkMapper;
        this.documentChunkBatchService = documentChunkBatchService;
        this.pollIntervalMs = pollIntervalMs;
        this.pollMaxAttempts = pollMaxAttempts;
    }

    public void parseAndChunkDocument(Long documentId, Long groupId) {
        log.info("Starting parse and chunk: documentId={}, groupId={}", documentId, groupId);

        Long ingestionRunId = null;
        try {
            int claimed = documentMapper.claimForProcessing(
                documentId,
                List.of(DocumentStatus.PENDING.name(), DocumentStatus.FAILED.name()),
                DocumentStatus.PROCESSING.name());

            if (claimed == 0) {
                log.info("Document {} could not be claimed for processing (not PENDING/FAILED, or already claimed), skipping", documentId);
                return;
            }
            log.info("Claimed document {} for processing", documentId);

            DocumentEntity doc = documentMapper.selectById(documentId);
            if (doc == null) {
                throw new IllegalArgumentException("Document not found: " + documentId);
            }

            // File bytes sent directly (kind: "file"), not a presigned URL — Docling's own SSRF
            // guard hard-rejects any URL resolving to a private/internal IP, which the compose
            // network's minio hostname always is (Phase 9 finding).
            byte[] fileBytes;
            try (InputStream download = objectStorageService.downloadObject(doc.getStorageBucket(), doc.getStorageObjectKey())) {
                fileBytes = download.readAllBytes();
            }

            String taskId = doclingClient.submitAsync(fileBytes, doc.getFileName());
            log.info("Submitted Docling task {} for documentId={}", taskId, documentId);

            DocumentIngestionRunEntity run = DocumentIngestionRunEntity.builder()
                .documentId(documentId)
                .doclingTaskId(taskId)
                .status(IngestionRunStatus.STREAMING.name())
                .build();
            documentIngestionRunMapper.insert(run);
            ingestionRunId = run.getId();

            DoclingTaskStatus status = pollUntilTerminal(taskId);
            if (status != DoclingTaskStatus.SUCCESS) {
                throw new IllegalStateException(
                    "Docling conversion did not succeed for task " + taskId + ": status=" + status);
            }

            InputStream resultStream = doclingClient.fetchResult(taskId);
            List<DoclingItem> items = doclingDocumentParser.parse(resultStream);
            List<Document> assembled = doclingChunkAssembler.assemble(items);

            if (assembled.isEmpty()) {
                log.warn("No chunks produced by Docling assembler: documentId={}, taskId={}", documentId, taskId);
                documentChunkMapper.deleteByIngestionRunId(ingestionRunId);
                documentIngestionRunMapper.updateStatus(ingestionRunId, IngestionRunStatus.FAILED.name());
                documentMapper.updateStatusAndReason(documentId,
                    DocumentStatus.NO_CHUNKS_FOUND.name(),
                    "No chunks produced by Docling assembler");
                return;
            }

            List<DocumentChunkEntity> chunkEntities = buildChunkEntities(
                assembled, documentId, groupId, ingestionRunId, doc.getFileName());

            documentChunkBatchService.insertShadowChunks(chunkEntities);
            log.info("Flushed {} shadow chunk rows for documentId={}, ingestionRunId={}",
                chunkEntities.size(), documentId, ingestionRunId);

            documentChunkBatchService.cutover(documentId, groupId, ingestionRunId);
            log.info("Parse and chunk complete for documentId={}", documentId);

        } catch (Exception e) {
            log.error("Parse and chunk failed for documentId={}", documentId, e);
            if (ingestionRunId != null) {
                documentChunkMapper.deleteByIngestionRunId(ingestionRunId);
                documentIngestionRunMapper.updateStatus(ingestionRunId, IngestionRunStatus.FAILED.name());
            }
            documentMapper.updateStatusAndReason(documentId,
                DocumentStatus.FAILED.name(),
                "Parse Stage: " + e.getMessage());
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    /**
     * Polls until Docling reports a terminal status (SUCCESS/FAILURE). Runs on Phase 2's virtual
     * thread executor, so the {@code Thread.sleep} between polls only parks a cheap virtual
     * thread, not a scarce platform thread (Section 12).
     */
    private DoclingTaskStatus pollUntilTerminal(String taskId) throws InterruptedException {
        for (int attempt = 0; attempt < pollMaxAttempts; attempt++) {
            DoclingTaskStatus status = doclingClient.pollStatus(taskId);
            if (status == DoclingTaskStatus.SUCCESS || status == DoclingTaskStatus.FAILURE) {
                return status;
            }
            if (pollIntervalMs > 0) {
                Thread.sleep(pollIntervalMs);
            }
        }
        throw new IllegalStateException(
            "Docling task " + taskId + " did not reach a terminal status within " + pollMaxAttempts + " poll attempts");
    }

    /**
     * Document -> DocumentChunkEntity (Section 14: chunkNumber/ingestion_run_id/is_current are
     * attached here, at the entity-building step — the assembler itself never knows about run
     * tracking or DB versioning).
     */
    private List<DocumentChunkEntity> buildChunkEntities(
            List<Document> chunks, Long documentId, Long groupId, Long ingestionRunId, String documentName) {

        LocalDateTime now = LocalDateTime.now();
        List<DocumentChunkEntity> entities = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = chunk.getMetadata();
            String chunkText = chunk.getText();

            ChunkMetadata metadata = ChunkMetadata.builder()
                .documentName(documentName)
                .sectionPath((String) meta.get("sectionPath"))
                .pageStart((Integer) meta.get("pageStart"))
                .pageEnd((Integer) meta.get("pageEnd"))
                .itemType((String) meta.get("itemType"))
                .length(chunkText.length())
                .tokenCount((int) Math.ceil(chunkText.length() / 4.0))
                .chunkStrategy("docling-structural-v1")
                .chunkIndex(i)
                .documentId(documentId)
                .groupId(groupId)
                .build();

            entities.add(DocumentChunkEntity.builder()
                .documentId(documentId)
                .groupId(groupId)
                .chunkNumber(i)
                .chunkText(chunkText)
                .metadataJson(metadata.toJson())
                .ingestionRunId(ingestionRunId)
                .isCurrent(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
        }

        return entities;
    }
}
