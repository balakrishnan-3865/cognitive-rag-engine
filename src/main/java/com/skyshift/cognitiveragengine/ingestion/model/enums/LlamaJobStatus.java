package com.skyshift.cognitiveragengine.ingestion.model.enums;

import java.util.Locale;

/**
 * LlamaParse job status values, confirmed live in Verification (02-verification.md Q4):
 * uppercase on the wire (PENDING/COMPLETED/FAILED/CANCELLED), read from {@code job.status}.
 */
public enum LlamaJobStatus {
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public static LlamaJobStatus fromWireValue(String wireValue) {
        if (wireValue == null) {
            throw new IllegalArgumentException("LlamaParse job status was null");
        }
        return LlamaJobStatus.valueOf(wireValue.toUpperCase(Locale.ROOT));
    }
}
