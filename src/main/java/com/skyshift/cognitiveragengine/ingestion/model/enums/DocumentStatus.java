package com.skyshift.cognitiveragengine.ingestion.model.enums;

public enum DocumentStatus {
    PENDING("Awaiting ingestion"),
    PROCESSING("Validation passed, acquiring ingestion lock"),
    INJECTING("Actively embedding and indexing (idempotency guard)"),
    READY("Successfully ingested and indexed"),
    NO_CHUNKS_FOUND("Ingestion skipped—no document chunks available"),
    FAILED("Permanent error, manual intervention needed");

    private final String description;

    DocumentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}