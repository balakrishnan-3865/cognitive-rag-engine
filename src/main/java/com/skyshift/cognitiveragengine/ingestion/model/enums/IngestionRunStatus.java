package com.skyshift.cognitiveragengine.ingestion.model.enums;

public enum IngestionRunStatus {
    STREAMING("Run in progress, chunks landing as shadow rows"),
    CUTOVER_COMPLETE("Run succeeded and was promoted to is_current=true"),
    FAILED("Run failed, shadow rows pending cleanup");

    private final String description;

    IngestionRunStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
