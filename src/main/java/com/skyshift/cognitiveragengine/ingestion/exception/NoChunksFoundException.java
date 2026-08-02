package com.skyshift.cognitiveragengine.ingestion.exception;

public class NoChunksFoundException extends RuntimeException {
    public NoChunksFoundException(String message) {
        super(message);
    }

    public NoChunksFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}