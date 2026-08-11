package com.skyshift.cognitiveragengine.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.document.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves DocumentController derives both groupId and uploadedUserId from the authenticated
 * principal - not a client-supplied groupId (upload/versions/revert) and not a hardcoded
 * uploadedUserId placeholder - the same IDOR shape closed for Assistant/Qa/ClaimsQuery in
 * IdorClosureIntegrationTest. DocumentService is mocked; this is about controller-level wiring,
 * not upload/storage logic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentIdorClosureIntegrationTest {

    private static final String RAW_PASSWORD = "DocIdor#Secure99";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private DocumentService documentService;

    private record LoggedInUser(Long id, Long groupId, String accessToken) {}

    private LoggedInUser registerAndLogin(String username, long groupId) throws Exception {
        String registerBody = objectMapper.writeValueAsString(Map.of(
            "username", username,
            "email", username + "@example.com",
            "password", RAW_PASSWORD,
            "firstName", "Doc",
            "lastName", "Idor"
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
    void uploadDocument_authenticated_derivesGroupIdAndUserIdFromPrincipal_ignoringSpoofedGroupId() throws Exception {
        LoggedInUser user = registerAndLogin("doc_idor_upload_user", 5);
        when(documentService.uploadDocument(any(), anyLong(), anyLong())).thenReturn(1L);

        MockMultipartFile file = new MockMultipartFile("file", "claim.pdf", "application/pdf", "dummy".getBytes());

        mockMvc.perform(multipart("/api/v1/documents/upload")
                .file(file)
                .param("groupId", "888888")
                .header("Authorization", "Bearer " + user.accessToken()))
            .andExpect(status().isCreated());

        verify(documentService).uploadDocument(any(), eq(user.groupId()), eq(user.id()));
    }

    @Test
    @Transactional
    void uploadNewVersion_authenticated_derivesGroupIdAndUserIdFromPrincipal_ignoringSpoofedGroupId() throws Exception {
        LoggedInUser user = registerAndLogin("doc_idor_version_user", 6);
        when(documentService.uploadNewVersion(anyLong(), anyLong(), any(), anyLong())).thenReturn(2L);

        MockMultipartFile file = new MockMultipartFile("file", "claim-v2.pdf", "application/pdf", "dummy".getBytes());

        mockMvc.perform(multipart("/api/v1/documents/42/versions")
                .file(file)
                .param("groupId", "777777")
                .header("Authorization", "Bearer " + user.accessToken()))
            .andExpect(status().isCreated());

        verify(documentService).uploadNewVersion(eq(42L), eq(user.groupId()), any(), eq(user.id()));
    }

    @Test
    @Transactional
    void revertToVersion_authenticated_derivesGroupIdFromPrincipal_ignoringSpoofedGroupId() throws Exception {
        LoggedInUser user = registerAndLogin("doc_idor_revert_user", 7);

        mockMvc.perform(post("/api/v1/documents/10/versions/9/revert")
                .param("groupId", "666666")
                .header("Authorization", "Bearer " + user.accessToken()))
            .andExpect(status().isNoContent());

        verify(documentService).revertToVersion(10L, 9L, user.groupId());
    }

    @Test
    @Transactional
    void uploadDocument_unauthenticated_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "claim.pdf", "application/pdf", "dummy".getBytes());

        mockMvc.perform(multipart("/api/v1/documents/upload").file(file))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void listDocuments_authenticated_derivesGroupIdAndUserIdFromPrincipal() throws Exception {
        LoggedInUser user = registerAndLogin("doc_idor_list_user", 8);
        when(documentService.listDocuments(anyLong(), anyLong())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/documents")
                .header("Authorization", "Bearer " + user.accessToken()))
            .andExpect(status().isOk());

        verify(documentService).listDocuments(eq(user.groupId()), eq(user.id()));
    }

    @Test
    @Transactional
    void listDocuments_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/documents"))
            .andExpect(status().isUnauthorized());
    }
}
