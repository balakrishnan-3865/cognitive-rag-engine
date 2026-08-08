package com.skyshift.cognitiveragengine.common.exception;

public class UncategorizedAgentException extends RuntimeException {

    public UncategorizedAgentException(String message) {
        super(message);
    }

    public UncategorizedAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
