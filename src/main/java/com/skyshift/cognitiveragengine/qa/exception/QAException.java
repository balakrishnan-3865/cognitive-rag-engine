package com.skyshift.cognitiveragengine.qa.exception;

public class QAException extends RuntimeException {
    public QAException(String message) {
        super(message);
    }

    public QAException(String message, Throwable cause) {
        super(message, cause);
    }
}