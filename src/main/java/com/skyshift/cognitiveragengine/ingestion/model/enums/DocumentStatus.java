package com.skyshift.cognitiveragengine.ingestion.model.enums;

public enum DocumentStatus {
    PENDING("Awaiting ingestion"),
    PROCESSING("Actively processing (idempotency guard)"),
    READY("Successfully ingested and indexed"),
    FAILED("Permanent error, manual intervention needed");

    private final String description;

    DocumentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}