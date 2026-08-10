package com.skyshift.cognitiveragengine.auth.service;

import com.skyshift.cognitiveragengine.auth.exception.InvalidCredentialsException;
import com.skyshift.cognitiveragengine.auth.jwt.RefreshTokenGenerator;
import com.skyshift.cognitiveragengine.auth.model.dto.LoginRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.TokenPairResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Uses dedicated 'login_test_*' users (created via direct insert with a known raw password,
 * hashed through the real PasswordEncoder bean) rather than the Flyway seed users - the seed
 * users' bcrypt hashes have no known plaintext, so login can't be exercised against them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AuthServiceIntegrationTest {

    private static final String RAW_PASSWORD = "LoginTest#Secure99";

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long insertTestUser(String username, boolean enabled) {
        String hash = passwordEncoder.encode(RAW_PASSWORD);
        jdbcTemplate.update(
            "insert into users (group_id, username, email, password_hash, first_name, last_name, role, enabled) " +
                "values (1, ?, ?, ?, 'Login', 'Test', 'USER', ?)",
            username, username + "@example.com", hash, enabled);
        return jdbcTemplate.queryForObject("select id from users where username = ?", Long.class, username);
    }

    @Test
    void login_correctCredentials_returnsTokensAndCreatesRefreshTokenRow() {
        Long userId = insertTestUser("login_test_ok", true);

        TokenPairResponse response = authService.login(new LoginRequest("login_test_ok", RAW_PASSWORD));

        assertNotNull(response.accessToken());
        assertNotNull(response.refreshToken());
        Integer rowCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId);
        assertEquals(1, rowCount);
    }

    @Test
    void login_wrongPassword_rejectedAndNoRowCreated() {
        Long userId = insertTestUser("login_test_wrongpw", true);

        assertThrows(InvalidCredentialsException.class,
            () -> authService.login(new LoginRequest("login_test_wrongpw", "totally-wrong-password")));

        Integer rowCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId);
        assertEquals(0, rowCount);
    }

    @Test
    void login_unknownUsername_rejectedSameAsWrongPassword() {
        assertThrows(InvalidCredentialsException.class,
            () -> authService.login(new LoginRequest("login_test_does_not_exist", RAW_PASSWORD)));
    }

    @Test
    void login_disabledUser_rejectedEvenWithCorrectPassword() {
        insertTestUser("login_test_disabled", false);

        assertThrows(InvalidCredentialsException.class,
            () -> authService.login(new LoginRequest("login_test_disabled", RAW_PASSWORD)));
    }

    @Test
    void login_updatesLastLoginAt_onlyOnSuccessfulAttempt() {
        Long userId = insertTestUser("login_test_lastlogin", true);

        assertThrows(InvalidCredentialsException.class,
            () -> authService.login(new LoginRequest("login_test_lastlogin", "wrong-password")));
        Timestamp afterFailure = jdbcTemplate.queryForObject(
            "select last_login_at from users where id = ?", Timestamp.class, userId);
        assertNull(afterFailure);

        authService.login(new LoginRequest("login_test_lastlogin", RAW_PASSWORD));

        Timestamp afterSuccess = jdbcTemplate.queryForObject(
            "select last_login_at from users where id = ?", Timestamp.class, userId);
        assertNotNull(afterSuccess);
    }

    @Test
    void login_secondSuccessfulLogin_overwritesRefreshTokenRow() {
        Long userId = insertTestUser("login_test_overwrite", true);

        TokenPairResponse first = authService.login(new LoginRequest("login_test_overwrite", RAW_PASSWORD));
        String firstHashInDb = jdbcTemplate.queryForObject(
            "select token_hash from refresh_tokens where user_id = ?", String.class, userId);
        assertEquals(RefreshTokenGenerator.hash(first.refreshToken()), firstHashInDb);

        TokenPairResponse second = authService.login(new LoginRequest("login_test_overwrite", RAW_PASSWORD));

        Integer rowCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId);
        assertEquals(1, rowCount);
        String secondHashInDb = jdbcTemplate.queryForObject(
            "select token_hash from refresh_tokens where user_id = ?", String.class, userId);
        assertNotEquals(firstHashInDb, secondHashInDb);
        assertNotEquals(RefreshTokenGenerator.hash(first.refreshToken()), secondHashInDb);
        assertNotEquals(first.refreshToken(), second.refreshToken());
    }
}
