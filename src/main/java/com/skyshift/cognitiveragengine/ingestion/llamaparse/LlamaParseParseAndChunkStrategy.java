package com.skyshift.cognitiveragengine.ingestion.llamaparse;

import com.skyshift.cognitiveragengine.common.exception.ParseException;
import com.skyshift.cognitiveragengine.ingestion.client.LlamaParseClient;
import com.skyshift.cognitiveragengine.ingestion.config.LlamaParseProperties;
import com.skyshift.cognitiveragengine.ingestion.model.enums.LlamaJobStatus;
import com.skyshift.cognitiveragengine.ingestion.parser.ParseAndChunkStrategy;
import com.skyshift.cognitiveragengine.ingestion.parser.StrategyResult;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Wraps LlamaParse's upload -> submit -> poll -> fetch -> parse -> assemble sequence behind
 * {@link ParseAndChunkStrategy}, mirroring {@code DoclingParseAndChunkStrategy}'s role for the
 * Docling path.
 */
@Component
@ConditionalOnProperty(name = "parser.strategy", havingValue = "llama")
public class LlamaParseParseAndChunkStrategy implements ParseAndChunkStrategy {

    private final LlamaParseClient llamaParseClient;
    private final LlamaDocumentParser llamaDocumentParser;
    private final LlamaChunkAssembler llamaChunkAssembler;
    private final LlamaParseProperties properties;

    public LlamaParseParseAndChunkStrategy(
            LlamaParseClient llamaParseClient,
            LlamaDocumentParser llamaDocumentParser,
            LlamaChunkAssembler llamaChunkAssembler,
            LlamaParseProperties properties) {
        this.llamaParseClient = llamaParseClient;
        this.llamaDocumentParser = llamaDocumentParser;
        this.llamaChunkAssembler = llamaChunkAssembler;
        this.properties = properties;
    }

    @Override
    public StrategyResult execute(byte[] fileBytes, String filename) {
        String fileId = llamaParseClient.uploadFile(fileBytes, filename);
        String jobId = llamaParseClient.submitParseJob(fileId, properties.tier(), properties.version());

        LlamaJobStatus status;
        try {
            status = pollUntilTerminal(jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (status != LlamaJobStatus.COMPLETED) {
            throw new IllegalStateException(
                "LlamaParse job did not complete for job " + jobId + ": status=" + status);
        }

        InputStream resultStream = llamaParseClient.fetchResult(jobId);
        List<LlamaItem> items;
        try {
            items = llamaDocumentParser.parse(resultStream);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        List<Document> assembled = llamaChunkAssembler.assemble(items);

        return new StrategyResult(jobId, "llama-structural-v1", assembled);
    }

    /**
     * Polls until LlamaParse reports a terminal status (COMPLETED/FAILED/CANCELLED). Runs on
     * Phase 2's virtual thread executor, so {@code Thread.sleep} between polls only parks a cheap
     * virtual thread, not a scarce platform thread — same rationale as Docling's poll loop.
     */
    private LlamaJobStatus pollUntilTerminal(String jobId) throws InterruptedException {
        for (int attempt = 0; attempt < properties.pollMaxAttempts(); attempt++) {
            LlamaJobStatus status = llamaParseClient.pollStatus(jobId);
            if (status == LlamaJobStatus.COMPLETED
                    || status == LlamaJobStatus.FAILED
                    || status == LlamaJobStatus.CANCELLED) {
                return status;
            }
            if (properties.pollIntervalMs() > 0) {
                Thread.sleep(properties.pollIntervalMs());
            }
        }
        throw new IllegalStateException(
            "LlamaParse job " + jobId + " did not reach a terminal status within "
                + properties.pollMaxAttempts() + " poll attempts");
    }
}
