package com.skyshift.cognitiveragengine.tools;

import com.skyshift.cognitiveragengine.assistant.config.AssistantProperties;
import com.skyshift.cognitiveragengine.common.observability.ObservabilityProperties;
import com.skyshift.cognitiveragengine.qa.model.DocumentBundle;
import com.skyshift.cognitiveragengine.qa.service.HybridChunkRetrievalService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the optional documentId this tool now reads out of the ReactAgent's ToolContext -
 * shared by both the Assistant chat and the Claims workflow, since both bind tools via the same
 * AssistantReactAgentFactory.createAgent(...). Absent (whole-corpus) behavior must be unaffected.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseToolTest {

    private static final Long GROUP_ID = 100L;
    private static final int TOP_K = 5;

    @Mock
    private HybridChunkRetrievalService hybridChunkRetrievalService;

    private KnowledgeBaseTool knowledgeBaseTool;

    @BeforeEach
    void setUp() {
        AssistantProperties assistantProperties = new AssistantProperties();
        assistantProperties.setTopKDefault(TOP_K);
        knowledgeBaseTool = new KnowledgeBaseTool(
                hybridChunkRetrievalService,
                assistantProperties,
                ObservationRegistry.NOOP,
                new ObservabilityProperties()
        );
    }

    @Test
    void searchKnowledgeBase_documentIdBoundInContext_scopesRetrievalToThatDocument() {
        ToolContext toolContext = new ToolContext(Map.of(
                ContextKeys.GROUP_ID_CONTEXT_KEY, GROUP_ID,
                ContextKeys.DOCUMENT_ID_CONTEXT_KEY, 42L
        ));
        when(hybridChunkRetrievalService.retrieveRelevantChunks("query", GROUP_ID, 42L, TOP_K))
                .thenReturn(new DocumentBundle(List.of()));

        knowledgeBaseTool.searchKnowledgeBase("query", toolContext);

        verify(hybridChunkRetrievalService).retrieveRelevantChunks("query", GROUP_ID, 42L, TOP_K);
    }

    @Test
    void searchKnowledgeBase_noDocumentIdInContext_searchesWholeGroup() {
        ToolContext toolContext = new ToolContext(Map.of(
                ContextKeys.GROUP_ID_CONTEXT_KEY, GROUP_ID
        ));
        when(hybridChunkRetrievalService.retrieveRelevantChunks("query", GROUP_ID, null, TOP_K))
                .thenReturn(new DocumentBundle(List.of()));

        knowledgeBaseTool.searchKnowledgeBase("query", toolContext);

        verify(hybridChunkRetrievalService).retrieveRelevantChunks("query", GROUP_ID, null, TOP_K);
    }

    @Test
    void searchKnowledgeBase_missingGroupId_throwsIllegalStateException() {
        ToolContext toolContext = new ToolContext(Map.of());

        assertThrows(IllegalStateException.class, () -> knowledgeBaseTool.searchKnowledgeBase("query", toolContext));
    }
}
