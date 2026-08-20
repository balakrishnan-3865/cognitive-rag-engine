package com.skyshift.cognitiveragengine.ingestion.parser;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Opaque outcome of a {@link ParseAndChunkStrategy} run. {@code taskId} is the parser
 * provider's own task/job identifier, persisted as-is into {@code docling_task_id}
 * regardless of which strategy produced it.
 */
public record StrategyResult(String taskId, String chunkStrategyName, List<Document> chunks) {
}
