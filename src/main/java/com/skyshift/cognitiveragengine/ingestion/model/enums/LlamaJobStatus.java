package com.skyshift.cognitiveragengine.ingestion.model.enums;

import java.util.Locale;

/**
 * LlamaParse job status values, uppercase on the wire, read from {@code job.status}.
 * PENDING/COMPLETED/FAILED/CANCELLED were confirmed live in Verification (02-verification.md Q4);
 * RUNNING (a non-terminal in-progress status between PENDING and COMPLETED) surfaced live during
 * Phase 5's end-to-end validation and wasn't previously observed.
 */
public enum LlamaJobStatus {
    PENDING,
    RUNNING,
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
