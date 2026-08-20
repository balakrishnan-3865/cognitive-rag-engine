package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentIngestionRunMapper;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentIngestionRunEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DocumentStatus;
import com.skyshift.cognitiveragengine.ingestion.model.enums.IngestionRunStatus;
import com.skyshift.cognitiveragengine.ingestion.parser.ParseAndChunkStrategy;
import com.skyshift.cognitiveragengine.ingestion.parser.StrategyResult;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7: the real submit -> poll -> stream -> assemble -> flush -> cutover flow (Section 4's
 * happy path, Section 5's failure path), against a mocked {@link ParseAndChunkStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class ParseAndChunkServiceTest {

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

    private ParseAndChunkService service;
    private final AtomicLong runIdSequence = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new ParseAndChunkService(
            documentMapper, objectStorageService, parseAndChunkStrategy, documentIngestionRunMapper,
            documentChunkMapper, documentChunkBatchService);
    }

    /** Stubs needed by every test that gets past the claim guard. Not called by the claim-lost test. */
    private void stubClaimSucceedsAndDocumentResolves() {
        when(documentMapper.claimForProcessing(
                DOCUMENT_ID, List.of(DocumentStatus.PENDING.name(), DocumentStatus.FAILED.name()),
                DocumentStatus.PROCESSING.name()))
            .thenReturn(1);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(DocumentEntity.builder()
            .id(DOCUMENT_ID)
            .storageBucket("bucket")
            .storageObjectKey("key.pdf")
            .fileName("policy.pdf")
            .build());
        when(objectStorageService.downloadObject("bucket", "key.pdf"))
            .thenAnswer(invocation -> new ByteArrayInputStream("fake pdf bytes".getBytes()));

        // Assigns a fresh id per insert call, mimicking MyBatis useGeneratedKeys. Lenient: some
        // tests exercise a strategy failure that never reaches the insert step at all.
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            DocumentIngestionRunEntity run = invocation.getArgument(0);
            run.setId(runIdSequence.incrementAndGet());
            return 1;
        }).when(documentIngestionRunMapper).insert(any());
    }

    @Test
    void happyPath_submitsPollsStreamsAssemblesFlushesAndCutsOver() throws Exception {
        stubClaimSucceedsAndDocumentResolves();

        Document chunk0 = new Document("First chunk", Map.of("sectionPath", "Intro"));
        Document chunk1 = new Document("Second chunk", Map.of("pageStart", 2, "pageEnd", 3));
        when(parseAndChunkStrategy.execute(any(byte[].class), eq("policy.pdf")))
            .thenReturn(new StrategyResult("task-1", "docling-structural-v1", List.of(chunk0, chunk1)));

        service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID);

        ArgumentCaptor<DocumentIngestionRunEntity> runCaptor = ArgumentCaptor.forClass(DocumentIngestionRunEntity.class);
        verify(documentIngestionRunMapper).insert(runCaptor.capture());
        assertThat(runCaptor.getValue().getDoclingTaskId()).isEqualTo("task-1");
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(IngestionRunStatus.STREAMING.name());
        Long runId = runCaptor.getValue().getId();

        ArgumentCaptor<List<DocumentChunkEntity>> entitiesCaptor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkBatchService).insertShadowChunks(entitiesCaptor.capture());
        List<DocumentChunkEntity> entities = entitiesCaptor.getValue();
        assertThat(entities).hasSize(2);
        assertThat(entities.get(0).getChunkNumber()).isEqualTo(0);
        assertThat(entities.get(1).getChunkNumber()).isEqualTo(1);
        assertThat(entities).allSatisfy(e -> {
            assertThat(e.getIngestionRunId()).isEqualTo(runId);
            assertThat(e.getIsCurrent()).isFalse();
            assertThat(e.getDocumentId()).isEqualTo(DOCUMENT_ID);
            assertThat(e.getGroupId()).isEqualTo(GROUP_ID);
        });

        verify(documentChunkBatchService).cutover(DOCUMENT_ID, GROUP_ID, runId);
        verify(documentMapper, never()).updateStatusAndReason(any(), any(), any());
        verify(documentChunkMapper, never()).deleteByIngestionRunId(any());
    }

    @Test
    void downstreamFailureAfterRunInsert_cleansUpShadowRows_marksRunAndDocumentFailed_neverCutsOver() throws Exception {
        stubClaimSucceedsAndDocumentResolves();
        // Run row insert happens after the strategy call returns; a mid-parse failure inside
        // the strategy is surfaced by execute() throwing before ever reaching that point, so
        // this test instead proves cleanup after a run row was already inserted (e.g. a
        // downstream flush failure) by having insertShadowChunks fail.
        when(parseAndChunkStrategy.execute(any(byte[].class), eq("policy.pdf")))
            .thenReturn(new StrategyResult("task-2", "docling-structural-v1", List.of(new Document("Chunk", Map.of()))));
        doThrow(new RuntimeException("shadow insert failed"))
            .when(documentChunkBatchService).insertShadowChunks(any());

        assertThrows(Exception.class, () -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));

        ArgumentCaptor<DocumentIngestionRunEntity> runCaptor = ArgumentCaptor.forClass(DocumentIngestionRunEntity.class);
        verify(documentIngestionRunMapper).insert(runCaptor.capture());
        Long runId = runCaptor.getValue().getId();

        verify(documentChunkMapper).deleteByIngestionRunId(runId);
        verify(documentIngestionRunMapper).updateStatus(runId, IngestionRunStatus.FAILED.name());
        verify(documentMapper).updateStatusAndReason(eq(DOCUMENT_ID), eq(DocumentStatus.FAILED.name()), any());
        verify(documentChunkBatchService, never()).cutover(any(), any(), any());
    }

    @Test
    void retryAfterFailure_strategyFailureLeavesNoRunRow_retrySucceedsIndependently() throws Exception {
        // Since the run row is now inserted only after the strategy call returns (Locked
        // Decision: ParseAndChunkService keeps sole ownership of the insert, timed right after
        // execute() succeeds), a strategy-internal failure (e.g. Docling terminal FAILURE) never
        // reaches the insert step at all — unlike the pre-refactor code, which inserted the run
        // row immediately after submit, before polling.
        stubClaimSucceedsAndDocumentResolves();
        when(parseAndChunkStrategy.execute(any(byte[].class), eq("policy.pdf")))
            .thenThrow(new IllegalStateException("Docling conversion did not succeed for task task-fail: status=FAILURE"))
            .thenReturn(new StrategyResult("task-retry", "docling-structural-v1", List.of(new Document("Chunk", Map.of()))));

        assertThrows(Exception.class, () -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));
        assertDoesNotThrow(() -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));

        verify(documentMapper).updateStatusAndReason(eq(DOCUMENT_ID), eq(DocumentStatus.FAILED.name()), any());
        verify(documentChunkMapper, never()).deleteByIngestionRunId(any());

        ArgumentCaptor<DocumentIngestionRunEntity> runCaptor = ArgumentCaptor.forClass(DocumentIngestionRunEntity.class);
        verify(documentIngestionRunMapper, times(1)).insert(runCaptor.capture());
        Long runId = runCaptor.getValue().getId();

        verify(documentChunkBatchService, times(1)).cutover(DOCUMENT_ID, GROUP_ID, runId);
    }

    @Test
    void assemblerProducesNoChunks_marksNoChunksFound_doesNotThrow_neverCutsOver() throws Exception {
        stubClaimSucceedsAndDocumentResolves();
        when(parseAndChunkStrategy.execute(any(byte[].class), eq("policy.pdf")))
            .thenReturn(new StrategyResult("task-empty", "docling-structural-v1", List.of()));

        assertDoesNotThrow(() -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));

        verify(documentMapper).updateStatusAndReason(eq(DOCUMENT_ID), eq(DocumentStatus.NO_CHUNKS_FOUND.name()), any());
        verify(documentChunkBatchService, never()).insertShadowChunks(any());
        verify(documentChunkBatchService, never()).cutover(any(), any(), any());
    }

    @Test
    void strategyThrows_beforeAnyRunRowExists_marksDocumentFailed_neverInsertsRun() throws Exception {
        // Poll-exhaustion is Docling's own concern now, covered by DoclingParseAndChunkStrategyTest.
        // Here we only prove ParseAndChunkService's failure handling when the strategy call itself
        // throws (no taskId ever produced, so ingestionRunId stays null going into the catch block).
        stubClaimSucceedsAndDocumentResolves();
        when(parseAndChunkStrategy.execute(any(byte[].class), eq("policy.pdf")))
            .thenThrow(new IllegalStateException("Docling task task-stuck did not reach a terminal status within 3 poll attempts"));

        assertThrows(Exception.class, () -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));

        verify(documentIngestionRunMapper, never()).insert(any());
        verify(documentChunkMapper, never()).deleteByIngestionRunId(any());
        verify(documentMapper).updateStatusAndReason(eq(DOCUMENT_ID), eq(DocumentStatus.FAILED.name()), any());
    }

    @Test
    void parseAndChunkDocument_whenClaimLost_skipsEntirely() {
        when(documentMapper.claimForProcessing(
                DOCUMENT_ID, List.of(DocumentStatus.PENDING.name(), DocumentStatus.FAILED.name()),
                DocumentStatus.PROCESSING.name()))
            .thenReturn(0);

        assertDoesNotThrow(() -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));

        verify(documentMapper, never()).selectById(anyLong());
        verify(parseAndChunkStrategy, never()).execute(any(), any());
    }
}
