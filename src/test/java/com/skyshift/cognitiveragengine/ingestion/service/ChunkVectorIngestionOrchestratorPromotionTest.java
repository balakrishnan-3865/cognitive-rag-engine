package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.document.mapper.DocumentMapper;
import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.ingestion.mapper.DocumentChunkMapper;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DocumentStatus;
import com.skyshift.cognitiveragengine.ingestion.vectorstore.VectorIngestionService;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.service.ElasticsearchChunkIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the Phase 2 "flip current version on READY" hook in
 * {@link ChunkVectorIngestionOrchestrator}: a version upload is only promoted to
 * isCurrentVersion=true once its ingestion pipeline actually succeeds.
 */
@ExtendWith(MockitoExtension.class)
class ChunkVectorIngestionOrchestratorPromotionTest {

    private static final Long DOCUMENT_ID = 42L;
    private static final Long GROUP_ID = 7L;

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private VectorIngestionService vectorIngestionService;

    @Mock
    private ElasticsearchChunkIndexService elasticsearchChunkIndexService;

    @Mock
    private VectorEmbeddingTransactionExecutor vectorEmbeddingTransactionExecutor;

    private ChunkVectorIngestionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ChunkVectorIngestionOrchestrator(
            documentChunkMapper, documentMapper, vectorIngestionService, elasticsearchChunkIndexService,
            vectorEmbeddingTransactionExecutor);
    }

    @Test
    void ingestVectorsAndIndexChunks_versionedDocument_promotesToCurrentOnReady() {
        DocumentEntity thisVersion = DocumentEntity.builder()
            .id(DOCUMENT_ID)
            .fileName("v2.pdf")
            .deleted(false)
            .rootDocumentId(1L)
            .build();
        DocumentEntity previousCurrent = DocumentEntity.builder()
            .id(1L)
            .isCurrentVersion(true)
            .build();

        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(thisVersion);
        when(documentMapper.updateStatusFromTo(DOCUMENT_ID, DocumentStatus.PROCESSING.name(), DocumentStatus.INJECTING.name()))
            .thenReturn(1);
        when(documentChunkMapper.selectByDocumentIdAndGroupId(DOCUMENT_ID, GROUP_ID))
            .thenReturn(List.of(chunk()));
        when(documentMapper.findCurrentVersionInLineage(1L, DOCUMENT_ID)).thenReturn(previousCurrent);
        when(documentMapper.flipCurrentVersion(1L, DOCUMENT_ID)).thenReturn(1);

        orchestrator.ingestVectorsAndIndexChunks(DOCUMENT_ID, GROUP_ID);

        verify(documentMapper).updateStatus(DOCUMENT_ID, DocumentStatus.READY.name());
        verify(documentMapper).flipCurrentVersion(1L, DOCUMENT_ID);
    }

    @Test
    void ingestVectorsAndIndexChunks_nonVersionedDocument_neverFlipsCurrentVersion() {
        DocumentEntity rootDocument = DocumentEntity.builder()
            .id(DOCUMENT_ID)
            .fileName("v1.pdf")
            .deleted(false)
            .rootDocumentId(null)
            .build();

        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(rootDocument);
        when(documentMapper.updateStatusFromTo(DOCUMENT_ID, DocumentStatus.PROCESSING.name(), DocumentStatus.INJECTING.name()))
            .thenReturn(1);
        when(documentChunkMapper.selectByDocumentIdAndGroupId(DOCUMENT_ID, GROUP_ID))
            .thenReturn(List.of(chunk()));

        orchestrator.ingestVectorsAndIndexChunks(DOCUMENT_ID, GROUP_ID);

        verify(documentMapper).updateStatus(DOCUMENT_ID, DocumentStatus.READY.name());
        verify(documentMapper, never()).flipCurrentVersion(anyLong(), anyLong());
    }

    @Test
    void ingestVectorsAndIndexChunks_lostPromotionRace_stillCompletesSuccessfully() {
        DocumentEntity thisVersion = DocumentEntity.builder()
            .id(DOCUMENT_ID)
            .fileName("v2.pdf")
            .deleted(false)
            .rootDocumentId(1L)
            .build();
        DocumentEntity previousCurrent = DocumentEntity.builder()
            .id(1L)
            .isCurrentVersion(true)
            .build();

        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(thisVersion);
        when(documentMapper.updateStatusFromTo(DOCUMENT_ID, DocumentStatus.PROCESSING.name(), DocumentStatus.INJECTING.name()))
            .thenReturn(1);
        when(documentChunkMapper.selectByDocumentIdAndGroupId(DOCUMENT_ID, GROUP_ID))
            .thenReturn(List.of(chunk()));
        when(documentMapper.findCurrentVersionInLineage(1L, DOCUMENT_ID)).thenReturn(previousCurrent);
        // Lost race: another concurrent version upload already superseded the previous current version.
        when(documentMapper.flipCurrentVersion(1L, DOCUMENT_ID)).thenReturn(0);

        assertDoesNotThrow(() -> orchestrator.ingestVectorsAndIndexChunks(DOCUMENT_ID, GROUP_ID));

        verify(documentMapper).updateStatus(DOCUMENT_ID, DocumentStatus.READY.name());
    }

    private DocumentChunkEntity chunk() {
        return DocumentChunkEntity.builder()
            .id(1L)
            .groupId(GROUP_ID)
            .documentId(DOCUMENT_ID)
            .chunkNumber(0)
            .chunkText("Some chunk text")
            .build();
    }
}
