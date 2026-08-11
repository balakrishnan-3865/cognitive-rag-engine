package com.skyshift.cognitiveragengine.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.auth.jwt.JwtTokenProvider;
import com.skyshift.cognitiveragengine.qa.model.dto.QAResponse;
import com.skyshift.cognitiveragengine.qa.service.QaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Phase 3 SecurityFilterChain gate: protected endpoints require a valid access token,
 * /api/v1/auth/** and /actuator/health stay reachable unauthenticated, and a token signed with a
 * different secret is rejected. QaService is mocked - this test is about the gate, not QA logic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigIntegrationTest {

    private static final String RAW_PASSWORD = "SecCfg#Secure99";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private QaService qaService;

    private String qaRequestJson() throws Exception {
        return objectMapper.writeValueAsString(Map.of("query", "what is covered?", "groupId", 1));
    }

    private void registerUser(String username) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "username", username,
            "email", username + "@example.com",
            "password", RAW_PASSWORD,
            "firstName", "Sec",
            "lastName", "Cfg"
        ));
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated());
    }

    private String obtainAccessToken(String username) throws Exception {
        registerUser(username);
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
    void protectedEndpoint_noAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/qa/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(qaRequestJson()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @Transactional
    void protectedEndpoint_validToken_returns200AndReachesController() throws Exception {
        when(qaService.askQuestion(anyString(), anyLong()))
            .thenReturn(new QAResponse(true, "", Collections.emptyList(), "the answer"));
        String accessToken = obtainAccessToken("sec_cfg_valid");

        mockMvc.perform(post("/api/v1/qa/ask")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(qaRequestJson()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value("the answer"));
    }

    @Test
    @Transactional
    void authRegisterEndpoint_remainsReachableWithoutAuthorizationHeader() throws Exception {
        registerUser("sec_cfg_permitlist");
    }

    @Test
    @Transactional
    void protectedEndpoint_tokenSignedWithDifferentSecret_returns401() throws Exception {
        JwtProperties otherSecretProperties = new JwtProperties(
            "a-completely-different-secret-key-at-least-32-bytes-long", 900, 604800);
        String foreignToken = new JwtTokenProvider(otherSecretProperties).issueAccessToken("sec_cfg_valid");

        mockMvc.perform(post("/api/v1/qa/ask")
                .header("Authorization", "Bearer " + foreignToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(qaRequestJson()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealth_remainsReachableUnauthenticated() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    void corsPreflight_allowedOrigin_returnsOkWithAllowOriginHeader() throws Exception {
        mockMvc.perform(options("/api/v1/qa/ask")
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
            .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
    }

    @Test
    void corsPreflight_disallowedOrigin_isForbiddenWithoutAllowOriginHeader() throws Exception {
        mockMvc.perform(options("/api/v1/qa/ask")
                .header("Origin", "http://evil.example.com")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @Transactional
    void actualRequest_allowedOrigin_carriesAllowOriginHeaderAndReachesController() throws Exception {
        when(qaService.askQuestion(anyString(), anyLong()))
            .thenReturn(new QAResponse(true, "", Collections.emptyList(), "the answer"));
        String accessToken = obtainAccessToken("sec_cfg_cors_valid");

        mockMvc.perform(post("/api/v1/qa/ask")
                .header("Origin", "http://localhost:4200")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(qaRequestJson()))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
            .andExpect(jsonPath("$.answer").value("the answer"));
    }
}
