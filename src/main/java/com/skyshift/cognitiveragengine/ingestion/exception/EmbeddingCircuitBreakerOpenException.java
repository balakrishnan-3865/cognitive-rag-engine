package com.skyshift.cognitiveragengine.ingestion.exception;

public class EmbeddingCircuitBreakerOpenException extends RuntimeException {
    public EmbeddingCircuitBreakerOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}
