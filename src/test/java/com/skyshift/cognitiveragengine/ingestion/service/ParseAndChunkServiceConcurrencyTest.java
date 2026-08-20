package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentIngestionRunMapper;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DocumentStatus;
import com.skyshift.cognitiveragengine.ingestion.parser.ParseAndChunkStrategy;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 concurrency guard: {@link ParseAndChunkService} must claim a document atomically
 * (WHERE status IN ('PENDING','FAILED')) instead of a check-then-write race (Section 19).
 *
 * <p>The real Docling happy-path/failure-path/retry flow is covered in
 * {@link ParseAndChunkServiceTest} (Phase 7). This file stays scoped to the claim guard only.
 */
@ExtendWith(MockitoExtension.class)
class ParseAndChunkServiceConcurrencyTest {

    private static final Long DOCUMENT_ID = 1L;
    private static final Long GROUP_ID = 2L;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private ParseAndChunkStrategy parseAndChunkStrategy;

    @Mock
    private DocumentIngestionRunMapper documentIngestionRunMapper;

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private DocumentChunkBatchService documentChunkBatchService;

    private ParseAndChunkService parseAndChunkService;

    @BeforeEach
    void setUp() {
        parseAndChunkService = new ParseAndChunkService(
            documentMapper, objectStorageService, parseAndChunkStrategy, documentIngestionRunMapper,
            documentChunkMapper, documentChunkBatchService);
    }

    @Test
    void parseAndChunkDocument_whenClaimLost_skipsWithoutTouchingDocument() {
        when(documentMapper.claimForProcessing(
                DOCUMENT_ID, List.of(DocumentStatus.PENDING.name(), DocumentStatus.FAILED.name()),
                DocumentStatus.PROCESSING.name()))
            .thenReturn(0);

        assertDoesNotThrow(() -> parseAndChunkService.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));

        verify(documentMapper, never()).selectById(anyLong());
        verify(parseAndChunkStrategy, never()).execute(any(), any());
        verify(documentChunkBatchService, never()).insertShadowChunks(any());
        verify(documentChunkBatchService, never()).cutover(any(), any(), any());
    }
}
