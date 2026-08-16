package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.ingestion.event.DocumentChunksCreatedEvent;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentIngestionRunMapper;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.IngestionRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 7: DocumentChunkBatchService's streaming-era responsibilities — flushing shadow batches
 * (no delete, no run creation, no is_current flip) and the atomic cutover (retire old current
 * rows, promote the new run's rows, mark the run CUTOVER_COMPLETE, publish the event — Section 2).
 */
@ExtendWith(MockitoExtension.class)
class DocumentChunkBatchServiceTest {

    private static final Long DOCUMENT_ID = 1L;
    private static final Long GROUP_ID = 2L;
    private static final Long RUN_ID = 99L;

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private DocumentIngestionRunMapper documentIngestionRunMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DocumentChunkBatchService service;

    @BeforeEach
    void setUp() {
        service = new DocumentChunkBatchService(documentChunkMapper, documentIngestionRunMapper, eventPublisher);
    }

    @Test
    void insertShadowChunks_splitsLargeListsIntoMultipleBatches_underPostgresParamLimit() {
        // 9 params per chunk row; a list large enough to force at least 2 batchInsertChunks calls.
        List<DocumentChunkEntity> chunks = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            chunks.add(chunk(i));
        }

        service.insertShadowChunks(chunks);

        ArgumentCaptor<List<DocumentChunkEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkMapper, times(2)).batchInsertChunks(captor.capture());

        int total = captor.getAllValues().stream().mapToInt(List::size).sum();
        assertThat(total).isEqualTo(5000);
        assertThat(captor.getAllValues()).allSatisfy(batch ->
            assertThat(batch).allSatisfy(c -> assertThat(c.getIsCurrent()).isFalse()));
    }

    @Test
    void insertShadowChunks_smallList_singleBatchCall() {
        List<DocumentChunkEntity> chunks = List.of(chunk(0), chunk(1));

        service.insertShadowChunks(chunks);

        verify(documentChunkMapper, times(1)).batchInsertChunks(anyList());
    }

    @Test
    void cutover_retiresOldRows_promotesNewRun_marksRunComplete_publishesEventInOrder() {
        service.cutover(DOCUMENT_ID, GROUP_ID, RUN_ID);

        InOrder order = inOrder(documentChunkMapper, documentIngestionRunMapper, eventPublisher);
        order.verify(documentChunkMapper).retireCurrentChunks(DOCUMENT_ID, GROUP_ID);
        order.verify(documentChunkMapper).promoteRunChunks(RUN_ID);
        order.verify(documentIngestionRunMapper).updateStatus(RUN_ID, IngestionRunStatus.CUTOVER_COMPLETE.name());
        order.verify(eventPublisher).publishEvent(any(DocumentChunksCreatedEvent.class));
    }

    @Test
    void cutover_publishesEventForCorrectDocumentAndGroup() {
        service.cutover(DOCUMENT_ID, GROUP_ID, RUN_ID);

        ArgumentCaptor<DocumentChunksCreatedEvent> captor = ArgumentCaptor.forClass(DocumentChunksCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        assertThat(captor.getValue().documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(captor.getValue().groupId()).isEqualTo(GROUP_ID);
    }

    private DocumentChunkEntity chunk(int index) {
        LocalDateTime now = LocalDateTime.now();
        return DocumentChunkEntity.builder()
            .documentId(DOCUMENT_ID)
            .groupId(GROUP_ID)
            .chunkNumber(index)
            .chunkText("chunk " + index)
            .ingestionRunId(RUN_ID)
            .isCurrent(false)
            .createdAt(now)
            .updatedAt(now)
            .build();
    }
}
