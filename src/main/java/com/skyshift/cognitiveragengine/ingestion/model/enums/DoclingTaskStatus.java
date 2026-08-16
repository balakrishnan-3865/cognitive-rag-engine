package com.skyshift.cognitiveragengine.ingestion.model.enums;

import java.util.Locale;

/**
 * Docling task status values, confirmed live in Phase 0 as lowercase on the wire
 * (pending/started/success/failure) — not SUCCESS/RUNNING/FAILURE as originally assumed.
 */
public enum DoclingTaskStatus {
    PENDING,
    STARTED,
    SUCCESS,
    FAILURE;

    public static DoclingTaskStatus fromWireValue(String wireValue) {
        if (wireValue == null) {
            throw new IllegalArgumentException("Docling task status was null");
        }
        return DoclingTaskStatus.valueOf(wireValue.toUpperCase(Locale.ROOT));
    }
}
