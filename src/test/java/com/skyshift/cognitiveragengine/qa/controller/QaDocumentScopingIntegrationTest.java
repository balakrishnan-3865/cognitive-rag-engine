package com.skyshift.cognitiveragengine.qa.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.qa.model.dto.QAResponse;
import com.skyshift.cognitiveragengine.qa.service.QaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves POST /api/v1/qa/ask forwards an optional documentId from the request body straight
 * through to QaService.askQuestion (single-document scoping for the UI's document picker), and
 * that a rejection from that resolution (invalid, not-ready, or cross-tenant documentId) reaches
 * the client as a clear HTTP 400 via GlobalExceptionHandler - not a 200 with a soft "unanswered"
 * body. QaService is mocked; this is about controller-level wiring, mirroring
 * IdorClosureIntegrationTest's approach for the existing query/groupId wiring.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QaDocumentScopingIntegrationTest {

    private static final String RAW_PASSWORD = "QaDocScope#Secure99";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private QaService qaService;

    private record LoggedInUser(Long id, Long groupId, String accessToken) {}

    private LoggedInUser registerAndLogin(String username, long groupId) throws Exception {
        String registerBody = objectMapper.writeValueAsString(Map.of(
            "username", username,
            "email", username + "@example.com",
            "password", RAW_PASSWORD,
            "firstName", "Qa",
            "lastName", "Scope"
        ));
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody))
            .andExpect(status().isCreated());

        jdbcTemplate.update("update users set group_id = ? where username = ?", groupId, username);

        String loginBody = objectMapper.writeValueAsString(Map.of("username", username, "password", RAW_PASSWORD));
        String response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(response).get("accessToken").asText();

        Long userId = jdbcTemplate.queryForObject("select id from users where username = ?", Long.class, username);
        return new LoggedInUser(userId, groupId, accessToken);
    }

    private String requestBody(String query, Object documentId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        if (documentId != null) {
            body.put("documentId", documentId);
        }
        return objectMapper.writeValueAsString(body);
    }

    @Test
    @Transactional
    void askQuestion_withDocumentId_forwardsItToQaService() throws Exception {
        LoggedInUser user = registerAndLogin("qa_scope_forward_user", 11);
        when(qaService.askQuestion(anyString(), anyLong(), anyLong()))
            .thenReturn(new QAResponse(true, "", Collections.emptyList(), "answer"));

        mockMvc.perform(post("/api/v1/qa/ask")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("what does this document say?", 42)))
            .andExpect(status().isOk());

        verify(qaService).askQuestion(eq("what does this document say?"), eq(user.groupId()), eq(42L));
    }

    @Test
    @Transactional
    void askQuestion_withoutDocumentId_forwardsNullDocumentId() throws Exception {
        LoggedInUser user = registerAndLogin("qa_scope_null_user", 12);
        when(qaService.askQuestion(anyString(), anyLong(), isNull()))
            .thenReturn(new QAResponse(true, "", Collections.emptyList(), "answer"));

        mockMvc.perform(post("/api/v1/qa/ask")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("what is covered?", null)))
            .andExpect(status().isOk());

        verify(qaService).askQuestion(eq("what is covered?"), eq(user.groupId()), isNull());
    }

    @Test
    @Transactional
    void askQuestion_invalidOrCrossTenantDocumentId_returns400BadRequestNotSoftFailure() throws Exception {
        LoggedInUser user = registerAndLogin("qa_scope_reject_user", 13);
        when(qaService.askQuestion(anyString(), anyLong(), anyLong()))
            .thenThrow(new BusinessException("Document not found or not ready: documentId=999"));

        mockMvc.perform(post("/api/v1/qa/ask")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("what does this document say?", 999)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Document not found or not ready: documentId=999"));
    }
}
