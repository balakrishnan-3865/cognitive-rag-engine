package com.skyshift.cognitiveragengine.qa.model;

import java.util.List;

/**
 * Wraps the outcome of a single retrieval attempt (dense or sparse).
 * Captures success/failure state without throwing exceptions immediately,
 * allowing orchestrator to handle both sources independently.
 */
public class RetrievalResult {
    private final String source;      // "dense" | "sparse"
    private final Object results;     // List<VectorHit> | List<KeywordHit> | null
    private final Exception error;    // null if success
    private final boolean success;

    private RetrievalResult(String source, Object results, Exception error) {
        this.source = source;
        this.results = results;
        this.error = error;
        this.success = error == null;
    }

    public static RetrievalResult success(String source, Object results) {
        return new RetrievalResult(source, results, null);
    }

    public static RetrievalResult failure(String source, Exception error) {
        return new RetrievalResult(source, null, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getSource() {
        return source;
    }

    public Object getResults() {
        return results;
    }

    public Exception getError() {
        return error;
    }

    public int getHitCount() {
        if (results instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }
}