package com.skyshift.cognitiveragengine.ingestion.model.dto;

/**
 * Wire shape confirmed live in Verification (02-verification.md Q4): the poll/result endpoint
 * wraps status under {@code job.status}, not a top-level {@code status} field.
 */
public record LlamaJobStatusResponse(Job job) {

    public record Job(String id, String status) {}
}
