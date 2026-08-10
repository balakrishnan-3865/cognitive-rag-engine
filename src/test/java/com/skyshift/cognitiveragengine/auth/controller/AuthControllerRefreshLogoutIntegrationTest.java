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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registers dedicated 'refresh_ctrl_*' users through the Phase 1 register endpoint, same
 * convention as AuthControllerLoginIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerRefreshLogoutIntegrationTest {

    private static final String RAW_PASSWORD = "RefreshCtrl#Secure99";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private void registerUser(String username) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "username", username,
            "email", username + "@example.com",
            "password", RAW_PASSWORD,
            "firstName", "Refresh",
            "lastName", "Ctrl",
            "groupId", 1
        ));
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
    }

    private record TokenPair(String accessToken, String refreshToken) {}

    private TokenPair login(String username) throws Exception {
        registerUser(username);
        String loginBody = objectMapper.writeValueAsString(Map.of("username", username, "password", RAW_PASSWORD));
        String response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var node = objectMapper.readTree(response);
        return new TokenPair(node.get("accessToken").asText(), node.get("refreshToken").asText());
    }

    @Test
    @Transactional
    void refresh_validToken_returns200WithNewTokenPair() throws Exception {
        TokenPair tokens = login("refresh_ctrl_valid");

        String refreshBody = objectMapper.writeValueAsString(Map.of("refreshToken", tokens.refreshToken()));
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").value(org.hamcrest.Matchers.not(tokens.refreshToken())));
    }

    @Test
    @Transactional
    void refresh_blankRefreshToken_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", ""));
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    void refresh_unknownToken_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", "not-a-real-token"));
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @Transactional
    void refresh_endpointRemainsReachableWithoutAuthorizationHeader() throws Exception {
        TokenPair tokens = login("refresh_ctrl_permitlist");

        String refreshBody = objectMapper.writeValueAsString(Map.of("refreshToken", tokens.refreshToken()));
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody))
            .andExpect(status().isOk());
    }

    @Test
    @Transactional
    void logout_authenticated_returns2xxAndRevokesRefreshToken() throws Exception {
        TokenPair tokens = login("refresh_ctrl_logout");

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().is2xxSuccessful());

        String refreshBody = objectMapper.writeValueAsString(Map.of("refreshToken", tokens.refreshToken()));
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void logout_withoutAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void logout_accessTokenIssuedBeforeLogout_stillWorksUntilItsOwnExpiry() throws Exception {
        TokenPair tokens = login("refresh_ctrl_accesssurvives");

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().is2xxSuccessful());

        // Access tokens are stateless and NOT revoked by logout - only the refresh_tokens row is
        // deleted. This is expected behavior per the plan, asserted explicitly here so it is
        // never "fixed" by accident: a second logout call with the same (still cryptographically
        // valid, unexpired) access token must still reach the controller and succeed.
        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + tokens.accessToken()))
            .andExpect(status().is2xxSuccessful());
    }

    @Test
    @Transactional
    void revoke_validToken_returns204WithoutRequiringAuthorizationHeader() throws Exception {
        TokenPair tokens = login("revoke_ctrl_valid");

        String body = objectMapper.writeValueAsString(Map.of("refreshToken", tokens.refreshToken()));
        mockMvc.perform(post("/api/v1/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNoContent());
    }

    @Test
    @Transactional
    void revoke_afterRevoke_thatRefreshTokenCanNoLongerBeUsedToRefresh() throws Exception {
        TokenPair tokens = login("revoke_ctrl_thenrefresh");
        String revokeBody = objectMapper.writeValueAsString(Map.of("refreshToken", tokens.refreshToken()));
        mockMvc.perform(post("/api/v1/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(revokeBody))
            .andExpect(status().isNoContent());

        String refreshBody = objectMapper.writeValueAsString(Map.of("refreshToken", tokens.refreshToken()));
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void revoke_unknownToken_returnsSame204AsAValidToken() throws Exception {
        // Same response for a token that was never issued as for one that genuinely gets deleted -
        // the endpoint must not let an unauthenticated caller probe token validity by status code.
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", "never-issued-by-anyone"));
        mockMvc.perform(post("/api/v1/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNoContent());
    }

    @Test
    @Transactional
    void revoke_blankRefreshToken_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", ""));
        mockMvc.perform(post("/api/v1/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }
}
