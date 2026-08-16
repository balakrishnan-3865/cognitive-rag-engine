package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.common.exception.ParseException;
import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.ingestion.client.DoclingClient;
import com.skyshift.cognitiveragengine.ingestion.docling.DoclingChunkAssembler;
import com.skyshift.cognitiveragengine.ingestion.docling.DoclingDocumentParser;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentIngestionRunMapper;
import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingItem;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentIngestionRunEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DocumentStatus;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingItemSource;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingTaskStatus;
import com.skyshift.cognitiveragengine.ingestion.model.enums.IngestionRunStatus;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7: the real Docling submit -> poll -> stream -> assemble -> flush -> cutover flow
 * (Section 4's happy path, Section 5's failure path).
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
    private DoclingClient doclingClient;

    @Mock
    private DocumentIngestionRunMapper documentIngestionRunMapper;

    @Mock
    private DoclingDocumentParser doclingDocumentParser;

    @Mock
    private DoclingChunkAssembler doclingChunkAssembler;

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private DocumentChunkBatchService documentChunkBatchService;

    private ParseAndChunkService service;
    private final AtomicLong runIdSequence = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new ParseAndChunkService(
            documentMapper, objectStorageService, doclingClient, documentIngestionRunMapper,
            doclingDocumentParser, doclingChunkAssembler, documentChunkMapper, documentChunkBatchService,
            0, 3);
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

        // Assigns a fresh id per insert call, mimicking MyBatis useGeneratedKeys.
        doAnswer(invocation -> {
            DocumentIngestionRunEntity run = invocation.getArgument(0);
            run.setId(runIdSequence.incrementAndGet());
            return 1;
        }).when(documentIngestionRunMapper).insert(any());
    }

    @Test
    void happyPath_submitsPollsStreamsAssemblesFlushesAndCutsOver() throws Exception {
        stubClaimSucceedsAndDocumentResolves();
        when(doclingClient.submitAsync(any(byte[].class), eq("policy.pdf"))).thenReturn("task-1");
        when(doclingClient.pollStatus("task-1")).thenReturn(DoclingTaskStatus.SUCCESS);
        InputStream resultStream = new ByteArrayInputStream(new byte[0]);
        when(doclingClient.fetchResult("task-1")).thenReturn(resultStream);
        when(doclingDocumentParser.parse(resultStream)).thenReturn(List.of(dummyItem()));

        Document chunk0 = new Document("First chunk", Map.of("sectionPath", "Intro"));
        Document chunk1 = new Document("Second chunk", Map.of("pageStart", 2, "pageEnd", 3));
        when(doclingChunkAssembler.assemble(any())).thenReturn(List.of(chunk0, chunk1));

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
    void midStreamFailure_cleansUpShadowRows_marksRunAndDocumentFailed_neverCutsOver() throws Exception {
        stubClaimSucceedsAndDocumentResolves();
        when(doclingClient.submitAsync(any(byte[].class), eq("policy.pdf"))).thenReturn("task-2");
        when(doclingClient.pollStatus("task-2")).thenReturn(DoclingTaskStatus.SUCCESS);
        InputStream resultStream = new ByteArrayInputStream(new byte[0]);
        when(doclingClient.fetchResult("task-2")).thenReturn(resultStream);
        when(doclingDocumentParser.parse(resultStream)).thenThrow(new ParseException("stream dropped mid-read"));

        assertThrows(Exception.class, () -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));

        ArgumentCaptor<DocumentIngestionRunEntity> runCaptor = ArgumentCaptor.forClass(DocumentIngestionRunEntity.class);
        verify(documentIngestionRunMapper).insert(runCaptor.capture());
        Long runId = runCaptor.getValue().getId();

        verify(documentChunkMapper).deleteByIngestionRunId(runId);
        verify(documentIngestionRunMapper).updateStatus(runId, IngestionRunStatus.FAILED.name());
        verify(documentMapper).updateStatusAndReason(eq(DOCUMENT_ID), eq(DocumentStatus.FAILED.name()), any());
        verify(documentChunkBatchService, never()).insertShadowChunks(any());
        verify(documentChunkBatchService, never()).cutover(any(), any(), any());
    }

    @Test
    void retryAfterFailure_secondRunGetsFreshId_succeedsIndependently() throws Exception {
        stubClaimSucceedsAndDocumentResolves();
        when(doclingClient.submitAsync(any(byte[].class), eq("policy.pdf")))
            .thenReturn("task-fail").thenReturn("task-retry");
        when(doclingClient.pollStatus("task-fail")).thenReturn(DoclingTaskStatus.FAILURE);
        when(doclingClient.pollStatus("task-retry")).thenReturn(DoclingTaskStatus.SUCCESS);
        InputStream resultStream = new ByteArrayInputStream(new byte[0]);
        when(doclingClient.fetchResult("task-retry")).thenReturn(resultStream);
        when(doclingDocumentParser.parse(resultStream)).thenReturn(List.of(dummyItem()));
        when(doclingChunkAssembler.assemble(any())).thenReturn(List.of(new Document("Chunk", Map.of())));

        assertThrows(Exception.class, () -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));
        assertDoesNotThrow(() -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));

        ArgumentCaptor<DocumentIngestionRunEntity> runCaptor = ArgumentCaptor.forClass(DocumentIngestionRunEntity.class);
        verify(documentIngestionRunMapper, times(2)).insert(runCaptor.capture());
        List<DocumentIngestionRunEntity> runs = runCaptor.getAllValues();
        assertThat(runs.get(0).getId()).isNotEqualTo(runs.get(1).getId());

        verify(documentChunkBatchService, times(1)).cutover(eq(DOCUMENT_ID), eq(GROUP_ID), eq(runs.get(1).getId()));
        verify(documentChunkBatchService, never()).cutover(eq(DOCUMENT_ID), eq(GROUP_ID), eq(runs.get(0).getId()));
    }

    @Test
    void assemblerProducesNoChunks_marksNoChunksFound_doesNotThrow_neverCutsOver() throws Exception {
        stubClaimSucceedsAndDocumentResolves();
        when(doclingClient.submitAsync(any(byte[].class), eq("policy.pdf"))).thenReturn("task-empty");
        when(doclingClient.pollStatus("task-empty")).thenReturn(DoclingTaskStatus.SUCCESS);
        InputStream resultStream = new ByteArrayInputStream(new byte[0]);
        when(doclingClient.fetchResult("task-empty")).thenReturn(resultStream);
        when(doclingDocumentParser.parse(resultStream)).thenReturn(List.of());
        when(doclingChunkAssembler.assemble(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));

        verify(documentMapper).updateStatusAndReason(eq(DOCUMENT_ID), eq(DocumentStatus.NO_CHUNKS_FOUND.name()), any());
        verify(documentChunkBatchService, never()).insertShadowChunks(any());
        verify(documentChunkBatchService, never()).cutover(any(), any(), any());
    }

    @Test
    void pollLoop_exhaustsMaxAttempts_failsClearly() throws Exception {
        stubClaimSucceedsAndDocumentResolves();
        when(doclingClient.submitAsync(any(byte[].class), eq("policy.pdf"))).thenReturn("task-stuck");
        when(doclingClient.pollStatus("task-stuck")).thenReturn(DoclingTaskStatus.STARTED);

        assertThrows(Exception.class, () -> service.parseAndChunkDocument(DOCUMENT_ID, GROUP_ID));

        verify(doclingClient, times(3)).pollStatus("task-stuck");
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
        verify(doclingClient, never()).submitAsync(any(), any());
    }

    private DoclingItem dummyItem() {
        return new DoclingItem("#/texts/0", DoclingItemSource.TEXT, "text", null, "hello", "body", List.of(1), null);
    }
}
