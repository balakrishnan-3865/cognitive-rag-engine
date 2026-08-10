package com.skyshift.cognitiveragengine.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.context.request.WebRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerSecurityTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleAuthenticationException_returns401StandardErrorResponseShape() {
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/v1/qa/ask");

        ResponseEntity<ErrorResponse> response = handler.handleAuthenticationException(
            new InsufficientAuthenticationException("Full authentication is required"), request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(401, response.getBody().status());
        assertEquals("/api/v1/qa/ask", response.getBody().path());
    }

    @Test
    void handleAccessDeniedException_returns403StandardErrorResponseShape() {
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/v1/qa/ask");

        ResponseEntity<ErrorResponse> response = handler.handleAccessDeniedException(
            new AccessDeniedException("Access is denied"), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(403, response.getBody().status());
        assertEquals("/api/v1/qa/ask", response.getBody().path());
    }
}
