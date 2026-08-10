package com.skyshift.cognitiveragengine.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;

final class SecurityErrorResponseWriter {

    private SecurityErrorResponseWriter() {
    }

    static void write(
        HttpServletResponse response,
        ObjectMapper objectMapper,
        HttpStatus status,
        String message,
        String path
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse errorResponse = new ErrorResponse(status.value(), status.getReasonPhrase(), message, path);
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
