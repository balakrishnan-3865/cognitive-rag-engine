package com.skyshift.cognitiveragengine.auth.controller;

import com.skyshift.cognitiveragengine.auth.model.dto.RegisterRequest;
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

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserSummaryResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Registration request received: username={}", request.username());

        UserSummaryResponse response = userService.register(request);

        log.info("User registered successfully: userId={}, username={}", response.id(), response.username());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
