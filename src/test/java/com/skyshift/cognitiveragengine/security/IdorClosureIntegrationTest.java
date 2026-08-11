package com.skyshift.cognitiveragengine.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.assistant.model.dto.AssistantResponse;
import com.skyshift.cognitiveragengine.assistant.service.AssistantService;
import com.skyshift.cognitiveragengine.qa.model.dto.QAResponse;
import com.skyshift.cognitiveragengine.qa.service.QaService;
import com.skyshift.cognitiveragengine.workflows.claims.model.dto.AssistantQueryResponse;
import com.skyshift.cognitiveragengine.workflows.claims.service.ClaimsAgentOrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Phase 4 IDOR closure: AssistantRequest/QARequest/AssistantQueryRequest no longer
 * accept userId/groupId from the client - the Assistant/Qa/ClaimsQuery controllers derive both
 * from the authenticated principal instead. Downstream services are mocked; this is about the
 * controller-level wiring, not RAG/agent logic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdorClosureIntegrationTest {

    private static final String RAW_PASSWORD = "IdorClose#Secure99";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AssistantService assistantService;

    @MockitoBean
    private QaService qaService;

    @MockitoBean
    private ClaimsAgentOrchestratorService claimsAgentOrchestratorService;

    private record LoggedInUser(Long id, Long groupId, String accessToken) {}

    private LoggedInUser registerAndLogin(String username, long groupId) throws Exception {
        String registerBody = objectMapper.writeValueAsString(Map.of(
            "username", username,
            "email", username + "@example.com",
            "password", RAW_PASSWORD,
            "firstName", "Idor",
            "lastName", "Close"
        ));
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody))
            .andExpect(status().isCreated());

        // Registration no longer accepts a client-supplied groupId (every new user defaults to
        // group 1) - set it directly to simulate a user who already belongs to a distinct group,
        // so this test can still prove per-principal groupId isolation.
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

    @Test
    @Transactional
    void assistantAsk_authenticated_derivesIdsFromPrincipal_ignoringSpoofedBodyFields() throws Exception {
        LoggedInUser user = registerAndLogin("idor_assistant_user", 5);
        when(assistantService.ask(anyString(), anyLong(), anyLong(), any()))
            .thenReturn(new AssistantResponse(true, "", Collections.emptyList(), "answer", null));

        String body = objectMapper.writeValueAsString(Map.of(
            "message", "what is my claim status?",
            "userId", 999999,
            "groupId", 888888
        ));

        mockMvc.perform(post("/api/v1/assistant/ask")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        verify(assistantService).ask(eq("what is my claim status?"), eq(user.groupId()), eq(user.id()), any());
    }

    @Test
    @Transactional
    void qaAsk_authenticated_derivesGroupIdFromPrincipal_ignoringSpoofedBodyFields() throws Exception {
        LoggedInUser user = registerAndLogin("idor_qa_user", 6);
        when(qaService.askQuestion(anyString(), anyLong()))
            .thenReturn(new QAResponse(true, "", Collections.emptyList(), "answer"));

        String body = objectMapper.writeValueAsString(Map.of(
            "query", "what is covered?",
            "groupId", 777777
        ));

        mockMvc.perform(post("/api/v1/qa/ask")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        verify(qaService).askQuestion(eq("what is covered?"), eq(user.groupId()));
    }

    @Test
    @Transactional
    void claimsQuery_authenticated_derivesIdsFromPrincipal_ignoringSpoofedBodyFields() throws Exception {
        LoggedInUser user = registerAndLogin("idor_claims_user", 7);
        when(claimsAgentOrchestratorService.query(anyString(), anyLong(), anyLong()))
            .thenReturn(new AssistantQueryResponse(true, "", "answer"));

        String body = objectMapper.writeValueAsString(Map.of(
            "query", "status of my claim?",
            "userId", 555555,
            "groupId", 444444
        ));

        mockMvc.perform(post("/api/v1/claims/query")
                .header("Authorization", "Bearer " + user.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        verify(claimsAgentOrchestratorService).query(eq("status of my claim?"), eq(user.groupId()), eq(user.id()));
    }

    @Test
    @Transactional
    void crossTenantCallers_eachOperateOnlyOnTheirOwnGroupId() throws Exception {
        LoggedInUser groupOneUser = registerAndLogin("idor_tenant_group1", 1);
        LoggedInUser groupTwoUser = registerAndLogin("idor_tenant_group2", 2);
        when(qaService.askQuestion(anyString(), anyLong()))
            .thenReturn(new QAResponse(true, "", Collections.emptyList(), "answer"));

        String body = objectMapper.writeValueAsString(Map.of("query", "covered?"));

        mockMvc.perform(post("/api/v1/qa/ask")
                .header("Authorization", "Bearer " + groupOneUser.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/qa/ask")
                .header("Authorization", "Bearer " + groupTwoUser.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        verify(qaService).askQuestion("covered?", groupOneUser.groupId());
        verify(qaService).askQuestion("covered?", groupTwoUser.groupId());
    }

    @Test
    @Transactional
    void assistantAsk_unauthenticated_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", "hi"));
        mockMvc.perform(post("/api/v1/assistant/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void qaAsk_unauthenticated_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("query", "hi"));
        mockMvc.perform(post("/api/v1/qa/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void claimsQuery_unauthenticated_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("query", "hi"));
        mockMvc.perform(post("/api/v1/claims/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnauthorized());
    }
}
