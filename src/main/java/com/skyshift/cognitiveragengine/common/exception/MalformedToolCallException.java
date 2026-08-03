package com.skyshift.cognitiveragengine.common.exception;

public class MalformedToolCallException extends RuntimeException {

    public MalformedToolCallException(String message) {
        super(message);
    }

    public MalformedToolCallException(String message, Throwable cause) {
        super(message, cause);
    }
}