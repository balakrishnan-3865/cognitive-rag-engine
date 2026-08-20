package com.skyshift.cognitiveragengine.ingestion.docling;

import com.skyshift.cognitiveragengine.ingestion.client.DoclingClient;
import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingItem;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingItemSource;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingTaskStatus;
import com.skyshift.cognitiveragengine.ingestion.parser.StrategyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wraps Docling's submit -> poll -> fetch -> parse -> assemble sequence behind
 * {@link com.skyshift.cognitiveragengine.ingestion.parser.ParseAndChunkStrategy}, replicating the
 * orchestration {@link com.skyshift.cognitiveragengine.ingestion.service.ParseAndChunkService}
 * used to own directly.
 */
@ExtendWith(MockitoExtension.class)
class DoclingParseAndChunkStrategyTest {

    @Mock
    private DoclingClient doclingClient;

    @Mock
    private DoclingDocumentParser doclingDocumentParser;

    @Mock
    private DoclingChunkAssembler doclingChunkAssembler;

    private DoclingParseAndChunkStrategy strategy;

    private void newStrategy(int pollMaxAttempts) {
        strategy = new DoclingParseAndChunkStrategy(
            doclingClient, doclingDocumentParser, doclingChunkAssembler, 0, pollMaxAttempts);
    }

    @Test
    void execute_submitsPollsFetchesParsesAndAssembles_returnsStrategyResult() throws Exception {
        newStrategy(3);
        when(doclingClient.submitAsync(any(byte[].class), eq("policy.pdf"))).thenReturn("task-1");
        when(doclingClient.pollStatus("task-1")).thenReturn(DoclingTaskStatus.SUCCESS);
        InputStream resultStream = new ByteArrayInputStream(new byte[0]);
        when(doclingClient.fetchResult("task-1")).thenReturn(resultStream);
        List<DoclingItem> items = List.of(dummyItem());
        when(doclingDocumentParser.parse(resultStream)).thenReturn(items);

        Document chunk0 = new Document("First chunk", Map.of("sectionPath", "Intro"));
        Document chunk1 = new Document("Second chunk", Map.of("pageStart", 2, "pageEnd", 3));
        when(doclingChunkAssembler.assemble(items)).thenReturn(List.of(chunk0, chunk1));

        StrategyResult result = strategy.execute("fake pdf bytes".getBytes(), "policy.pdf");

        assertThat(result.taskId()).isEqualTo("task-1");
        assertThat(result.chunkStrategyName()).isEqualTo("docling-structural-v1");
        assertThat(result.chunks()).containsExactly(chunk0, chunk1);
    }

    @Test
    void execute_nonSuccessTerminalStatus_throws() {
        newStrategy(3);
        when(doclingClient.submitAsync(any(byte[].class), eq("policy.pdf"))).thenReturn("task-fail");
        when(doclingClient.pollStatus("task-fail")).thenReturn(DoclingTaskStatus.FAILURE);

        assertThrows(IllegalStateException.class,
            () -> strategy.execute("bytes".getBytes(), "policy.pdf"));
    }

    @Test
    void execute_pollLoopExhaustsMaxAttempts_throws() {
        newStrategy(3);
        when(doclingClient.submitAsync(any(byte[].class), eq("policy.pdf"))).thenReturn("task-stuck");
        when(doclingClient.pollStatus("task-stuck")).thenReturn(DoclingTaskStatus.STARTED);

        assertThrows(IllegalStateException.class,
            () -> strategy.execute("bytes".getBytes(), "policy.pdf"));

        verify(doclingClient, times(3)).pollStatus("task-stuck");
    }

    private DoclingItem dummyItem() {
        return new DoclingItem("#/texts/0", DoclingItemSource.TEXT, "text", null, "hello", "body", List.of(1), null);
    }
}
