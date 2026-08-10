package com.skyshift.cognitiveragengine.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JwtAccessDeniedHandler(ObjectMapper objectMapper) {
        // See JwtAuthenticationEntryPoint - the shared Jackson 2 ObjectMapper bean has no
        // JSR-310 module under Boot 4, so a JavaTimeModule-equipped copy is used instead.
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
    }

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        AccessDeniedException accessDeniedException
    ) throws IOException {
        SecurityErrorResponseWriter.write(
            response,
            objectMapper,
            HttpStatus.FORBIDDEN,
            "Access is denied",
            request.getRequestURI()
        );
    }
}
