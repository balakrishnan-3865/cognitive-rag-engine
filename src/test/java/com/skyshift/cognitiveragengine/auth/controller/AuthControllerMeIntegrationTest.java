package com.skyshift.cognitiveragengine.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registers dedicated 'me_ctrl_*' users through the Phase 1 register endpoint, same convention
 * as AuthControllerLoginIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerMeIntegrationTest {

    private static final String RAW_PASSWORD = "MeCtrl#Secure99";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String registerAndLogin(String username) throws Exception {
        String registerBody = objectMapper.writeValueAsString(Map.of(
            "username", username,
            "email", username + "@example.com",
            "password", RAW_PASSWORD,
            "firstName", "Me",
            "lastName", "Ctrl",
            "groupId", 1
        ));
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody))
            .andExpect(status().isCreated());

        String loginBody = objectMapper.writeValueAsString(Map.of("username", username, "password", RAW_PASSWORD));
        String response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    @Test
    @Transactional
    void me_authenticated_returns200WithOwnProfile() throws Exception {
        String accessToken = registerAndLogin("me_ctrl_valid");

        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("me_ctrl_valid"))
            .andExpect(jsonPath("$.email").value("me_ctrl_valid@example.com"))
            .andExpect(jsonPath("$.firstName").value("Me"))
            .andExpect(jsonPath("$.lastName").value("Ctrl"))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.groupId").value(1))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @Transactional
    void me_withoutAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void me_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer not-a-real-token"))
            .andExpect(status().isUnauthorized());
    }
}
