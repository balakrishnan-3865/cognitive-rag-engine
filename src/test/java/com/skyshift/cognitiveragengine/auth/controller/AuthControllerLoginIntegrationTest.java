package com.skyshift.cognitiveragengine.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registers dedicated 'login_ctrl_*' users through the Phase 1 register endpoint (or, for the
 * disabled case, a direct insert) rather than using the Flyway seed users - their bcrypt hashes
 * have no known plaintext, so login can't be exercised against them. See jwt-authentication-plan.md
 * Phase 2 test cases for the plan's original "seed users" wording.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerLoginIntegrationTest {

    private static final String RAW_PASSWORD = "LoginCtrl#Secure99";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private void registerUser(String username) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "username", username,
            "email", username + "@example.com",
            "password", RAW_PASSWORD,
            "firstName", "Login",
            "lastName", "Ctrl",
            "groupId", 1
        ));
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
    }

    private String loginJson(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }

    @Test
    @Transactional
    void login_validCredentials_returns200WithWellFormedTokens() throws Exception {
        registerUser("login_ctrl_valid");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("login_ctrl_valid", RAW_PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    @Transactional
    void login_wrongPassword_returns401GenericErrorResponse() throws Exception {
        registerUser("login_ctrl_wrongpw");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("login_ctrl_wrongpw", "not-the-right-password")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @Transactional
    void login_unknownUsername_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("login_ctrl_does_not_exist", RAW_PASSWORD)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void login_disabledUser_returns401() throws Exception {
        String hash = passwordEncoder.encode(RAW_PASSWORD);
        jdbcTemplate.update(
            "insert into users (group_id, username, email, password_hash, first_name, last_name, role, enabled) " +
                "values (1, 'login_ctrl_disabled', 'login_ctrl_disabled@example.com', ?, 'Login', 'Ctrl', 'USER', false)",
            hash);

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("login_ctrl_disabled", RAW_PASSWORD)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void login_missingFields_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", ""));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void login_responseBodyNeverLeaksPasswordHashOrInternalIds() throws Exception {
        registerUser("login_ctrl_noleak");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("login_ctrl_noleak", RAW_PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.userId").doesNotExist())
            .andExpect(jsonPath("$.groupId").doesNotExist());
    }
}
