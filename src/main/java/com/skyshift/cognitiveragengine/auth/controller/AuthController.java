package com.skyshift.cognitiveragengine.auth.controller;

import com.skyshift.cognitiveragengine.auth.model.dto.LoginRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.RegisterRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.TokenPairResponse;
import com.skyshift.cognitiveragengine.auth.service.AuthService;
import com.skyshift.cognitiveragengine.user.model.dto.UserSummaryResponse;
import com.skyshift.cognitiveragengine.user.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final AuthService authService;

    public AuthController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserSummaryResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Registration request received: username={}", request.username());

        UserSummaryResponse response = userService.register(request);

        log.info("User registered successfully: userId={}, username={}", response.id(), response.username());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenPairResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login request received: username={}", request.username());

        TokenPairResponse response = authService.login(request);

        log.info("Login successful: username={}", request.username());

        return ResponseEntity.ok(response);
    }
}
