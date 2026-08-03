package com.skyshift.cognitiveragengine.common.exception;

public class RecursionLimitExceededException extends RuntimeException {

    public RecursionLimitExceededException(String message) {
        super(message);
    }

    public RecursionLimitExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}