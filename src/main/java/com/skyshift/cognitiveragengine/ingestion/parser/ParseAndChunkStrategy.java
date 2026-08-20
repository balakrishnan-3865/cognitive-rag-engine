package com.skyshift.cognitiveragengine.ingestion.parser;

/**
 * Hides a parser provider's submit/poll/fetch/parse/assemble internals behind a single call.
 * {@link ParseAndChunkService} owns document claiming, run-row bookkeeping, and shadow-insert
 * / cutover — a strategy only turns raw file bytes into assembled chunks.
 */
public interface ParseAndChunkStrategy {

    StrategyResult execute(byte[] fileBytes, String filename);
}
