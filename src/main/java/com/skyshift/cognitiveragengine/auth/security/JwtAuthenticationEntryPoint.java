package com.skyshift.cognitiveragengine.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        // Spring's autoconfigured Jackson 2 ObjectMapper bean has no JSR-310 module registered
        // by default under Boot 4 (HTTP message conversion now goes through Jackson 3, which has
        // java.time support built in) - copy rather than mutate the shared singleton bean.
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
    }

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException authException
    ) throws IOException {
        SecurityErrorResponseWriter.write(
            response,
            objectMapper,
            HttpStatus.UNAUTHORIZED,
            "Full authentication is required to access this resource",
            request.getRequestURI()
        );
    }
}
