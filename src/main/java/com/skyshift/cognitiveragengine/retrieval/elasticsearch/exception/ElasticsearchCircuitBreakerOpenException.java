package com.skyshift.cognitiveragengine.retrieval.elasticsearch.exception;

import java.io.IOException;

public class ElasticsearchCircuitBreakerOpenException extends IOException {
    public ElasticsearchCircuitBreakerOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}
