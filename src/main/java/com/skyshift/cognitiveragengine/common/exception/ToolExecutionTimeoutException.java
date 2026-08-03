package com.skyshift.cognitiveragengine.common.exception;

public class ToolExecutionTimeoutException extends RuntimeException {

    public ToolExecutionTimeoutException(String message) {
        super(message);
    }

    public ToolExecutionTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}