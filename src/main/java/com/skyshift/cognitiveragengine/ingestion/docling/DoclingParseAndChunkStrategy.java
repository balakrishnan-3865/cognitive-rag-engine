package com.skyshift.cognitiveragengine.ingestion.docling;

import com.skyshift.cognitiveragengine.common.exception.ParseException;
import com.skyshift.cognitiveragengine.ingestion.client.DoclingClient;
import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingItem;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingTaskStatus;
import com.skyshift.cognitiveragengine.ingestion.parser.ParseAndChunkStrategy;
import com.skyshift.cognitiveragengine.ingestion.parser.StrategyResult;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Wraps Docling's submit -> poll -> fetch -> parse -> assemble sequence behind
 * {@link ParseAndChunkStrategy} — the orchestration {@code ParseAndChunkService} used to own
 * directly (Phase 7's original flow), unchanged in substance.
 */
@Component
@ConditionalOnProperty(name = "parser.strategy", havingValue = "docling", matchIfMissing = true)
public class DoclingParseAndChunkStrategy implements ParseAndChunkStrategy {

    private final DoclingClient doclingClient;
    private final DoclingDocumentParser doclingDocumentParser;
    private final DoclingChunkAssembler doclingChunkAssembler;
    private final long pollIntervalMs;
    private final int pollMaxAttempts;

    public DoclingParseAndChunkStrategy(
            DoclingClient doclingClient,
            DoclingDocumentParser doclingDocumentParser,
            DoclingChunkAssembler doclingChunkAssembler,
            @Value("${docling.poll-interval-ms:2000}") long pollIntervalMs,
            @Value("${docling.poll-max-attempts:150}") int pollMaxAttempts) {
        this.doclingClient = doclingClient;
        this.doclingDocumentParser = doclingDocumentParser;
        this.doclingChunkAssembler = doclingChunkAssembler;
        this.pollIntervalMs = pollIntervalMs;
        this.pollMaxAttempts = pollMaxAttempts;
    }

    @Override
    public StrategyResult execute(byte[] fileBytes, String filename) {
        String taskId = doclingClient.submitAsync(fileBytes, filename);

        DoclingTaskStatus status;
        try {
            status = pollUntilTerminal(taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (status != DoclingTaskStatus.SUCCESS) {
            throw new IllegalStateException(
                "Docling conversion did not succeed for task " + taskId + ": status=" + status);
        }

        InputStream resultStream = doclingClient.fetchResult(taskId);
        List<DoclingItem> items;
        try {
            items = doclingDocumentParser.parse(resultStream);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        List<Document> assembled = doclingChunkAssembler.assemble(items);

        return new StrategyResult(taskId, "docling-structural-v1", assembled);
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
}
