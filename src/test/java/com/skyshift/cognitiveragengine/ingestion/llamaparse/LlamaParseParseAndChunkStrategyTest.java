package com.skyshift.cognitiveragengine.ingestion.llamaparse;

import com.skyshift.cognitiveragengine.ingestion.client.LlamaParseClient;
import com.skyshift.cognitiveragengine.ingestion.config.LlamaParseProperties;
import com.skyshift.cognitiveragengine.ingestion.model.enums.LlamaJobStatus;
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
 * Wraps LlamaParse's upload -> submit -> poll -> fetch -> parse -> assemble sequence behind
 * {@link com.skyshift.cognitiveragengine.ingestion.parser.ParseAndChunkStrategy}, mirroring
 * {@code DoclingParseAndChunkStrategyTest}'s coverage for the Docling path.
 */
@ExtendWith(MockitoExtension.class)
class LlamaParseParseAndChunkStrategyTest {

    @Mock
    private LlamaParseClient llamaParseClient;

    @Mock
    private LlamaDocumentParser llamaDocumentParser;

    @Mock
    private LlamaChunkAssembler llamaChunkAssembler;

    private LlamaParseParseAndChunkStrategy strategy;

    private void newStrategy(int pollMaxAttempts) {
        LlamaParseProperties properties = new LlamaParseProperties(
            "https://api.cloud.llamaindex.ai", "test-api-key", "cost_effective", "latest", 0, pollMaxAttempts);
        strategy = new LlamaParseParseAndChunkStrategy(
            llamaParseClient, llamaDocumentParser, llamaChunkAssembler, properties);
    }

    @Test
    void execute_uploadsSubmitsPollsFetchesParsesAndAssembles_returnsStrategyResult() throws Exception {
        newStrategy(3);
        when(llamaParseClient.uploadFile(any(byte[].class), eq("policy.pdf"))).thenReturn("file-1");
        when(llamaParseClient.submitParseJob("file-1", "cost_effective", "latest")).thenReturn("job-1");
        when(llamaParseClient.pollStatus("job-1")).thenReturn(LlamaJobStatus.COMPLETED);
        InputStream resultStream = new ByteArrayInputStream(new byte[0]);
        when(llamaParseClient.fetchResult("job-1")).thenReturn(resultStream);
        List<LlamaItem> items = List.of(dummyItem());
        when(llamaDocumentParser.parse(resultStream)).thenReturn(items);

        Document chunk0 = new Document("First chunk", Map.of("sectionPath", "Intro"));
        Document chunk1 = new Document("Second chunk", Map.of("pageStart", 2, "pageEnd", 3));
        when(llamaChunkAssembler.assemble(items)).thenReturn(List.of(chunk0, chunk1));

        StrategyResult result = strategy.execute("fake pdf bytes".getBytes(), "policy.pdf");

        assertThat(result.taskId()).isEqualTo("job-1");
        assertThat(result.chunkStrategyName()).isEqualTo("llama-structural-v1");
        assertThat(result.chunks()).containsExactly(chunk0, chunk1);
    }

    @Test
    void execute_failedTerminalStatus_throws() {
        newStrategy(3);
        when(llamaParseClient.uploadFile(any(byte[].class), eq("policy.pdf"))).thenReturn("file-1");
        when(llamaParseClient.submitParseJob("file-1", "cost_effective", "latest")).thenReturn("job-fail");
        when(llamaParseClient.pollStatus("job-fail")).thenReturn(LlamaJobStatus.FAILED);

        assertThrows(IllegalStateException.class,
            () -> strategy.execute("bytes".getBytes(), "policy.pdf"));
    }

    @Test
    void execute_cancelledTerminalStatus_throws() {
        newStrategy(3);
        when(llamaParseClient.uploadFile(any(byte[].class), eq("policy.pdf"))).thenReturn("file-1");
        when(llamaParseClient.submitParseJob("file-1", "cost_effective", "latest")).thenReturn("job-cancel");
        when(llamaParseClient.pollStatus("job-cancel")).thenReturn(LlamaJobStatus.CANCELLED);

        assertThrows(IllegalStateException.class,
            () -> strategy.execute("bytes".getBytes(), "policy.pdf"));
    }

    @Test
    void execute_pollLoopExhaustsMaxAttempts_throws() {
        newStrategy(3);
        when(llamaParseClient.uploadFile(any(byte[].class), eq("policy.pdf"))).thenReturn("file-1");
        when(llamaParseClient.submitParseJob("file-1", "cost_effective", "latest")).thenReturn("job-stuck");
        when(llamaParseClient.pollStatus("job-stuck")).thenReturn(LlamaJobStatus.PENDING);

        assertThrows(IllegalStateException.class,
            () -> strategy.execute("bytes".getBytes(), "policy.pdf"));

        verify(llamaParseClient, times(3)).pollStatus("job-stuck");
    }

    private LlamaItem dummyItem() {
        return new LlamaItem("text", null, "hello", List.of(1), null);
    }
}
