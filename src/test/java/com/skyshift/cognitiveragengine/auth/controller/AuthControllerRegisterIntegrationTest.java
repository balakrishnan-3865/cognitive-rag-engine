package com.skyshift.cognitiveragengine.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerRegisterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from users where username like 'reg_test_%'");
    }

    @Test
    @Transactional
    void register_validRequest_createsUserAndReturns201() throws Exception {
        String body = registerJson("reg_test_valid", "reg_test_valid@example.com", "Abcdefgh1234!");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.username").value("reg_test_valid"))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.password").doesNotExist());

        String storedHash = jdbcTemplate.queryForObject(
            "select password_hash from users where username = 'reg_test_valid'", String.class);
        assertTrue(storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$"));
        assertTrue(!storedHash.equals("Abcdefgh1234!"));
    }

    @Test
    @Transactional
    void register_duplicateUsername_returns409AndDoesNotInsert() throws Exception {
        String body = registerJson("jsmith", "reg_test_dupuser@example.com", "Abcdefgh1234!");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict());

        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from users where email = 'reg_test_dupuser@example.com'", Integer.class);
        assertEquals(0, count);
    }

    @Test
    @Transactional
    void register_duplicateEmail_returns409AndDoesNotInsert() throws Exception {
        String body = registerJson("reg_test_dupemail", "jane.smith@example.com", "Abcdefgh1234!");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict());

        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from users where username = 'reg_test_dupemail'", Integer.class);
        assertEquals(0, count);
    }

    @Test
    @Transactional
    void register_policyViolatingPassword_returns400AndDoesNotInsert() throws Exception {
        String body = registerJson("reg_test_weakpw", "reg_test_weakpw@example.com", "short");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());

        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from users where username = 'reg_test_weakpw'", Integer.class);
        assertEquals(0, count);
    }

    @Test
    @Transactional
    void register_blankPassword_returns400() throws Exception {
        String body = registerJson("reg_test_blankpw", "reg_test_blankpw@example.com", "");

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void register_concurrentDuplicateUsername_exactlyOneSucceeds() throws Exception {
        String username = "reg_test_race";
        String bodyA = registerJson(username, "reg_test_race_a@example.com", "Abcdefgh1234!");
        String bodyB = registerJson(username, "reg_test_race_b@example.com", "Abcdefgh1234!");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        try {
            Future<Integer> futureA = executor.submit(() -> performRegister(bodyA, ready, go));
            Future<Integer> futureB = executor.submit(() -> performRegister(bodyB, ready, go));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            int statusA = futureA.get(10, TimeUnit.SECONDS);
            int statusB = futureB.get(10, TimeUnit.SECONDS);

            List<Integer> results = List.of(statusA, statusB);
            assertTrue(results.contains(201), "expected exactly one 201, got: " + results);
            assertTrue(results.contains(409), "expected exactly one 409, got: " + results);

            Integer count = jdbcTemplate.queryForObject(
                "select count(*) from users where username = ?", Integer.class, username);
            assertEquals(1, count);
        } finally {
            executor.shutdownNow();
            jdbcTemplate.update("delete from users where username = ?", username);
        }
    }

    private int performRegister(String body, CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await();
            MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andReturn();
            return result.getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String registerJson(String username, String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "username", username,
            "email", email,
            "password", password,
            "firstName", "Test",
            "lastName", "User",
            "groupId", 1
        ));
    }
}
