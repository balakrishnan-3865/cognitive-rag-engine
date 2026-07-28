package com.skyshift.cognitiveragengine.retrieval.vectorstore.exception;

public class VectorSearchException extends RuntimeException {
    public VectorSearchException(String message) {
        super(message);
    }

    public VectorSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}