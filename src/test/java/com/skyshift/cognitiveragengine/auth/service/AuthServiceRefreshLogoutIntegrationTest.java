package com.skyshift.cognitiveragengine.auth.service;

import com.skyshift.cognitiveragengine.auth.exception.InvalidRefreshTokenException;
import com.skyshift.cognitiveragengine.auth.jwt.RefreshTokenGenerator;
import com.skyshift.cognitiveragengine.auth.model.dto.LoginRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.RefreshRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.TokenPairResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real Postgres + real AuthService, no mocks - proves refresh rotation is atomic/reuse-resistant
 * and logout genuinely revokes. Uses dedicated 'refresh_test_*' / 'logout_test_*' users inserted
 * directly (known raw password hashed through the real PasswordEncoder bean), same convention as
 * AuthServiceIntegrationTest. The concurrency test needs cross-connection visibility of setup data
 * mid-test, so this class does NOT use class-level @Transactional like its sibling - each
 * non-concurrent test is individually @Transactional for auto-rollback, and the concurrency test
 * cleans up manually.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthServiceRefreshLogoutIntegrationTest {

    private static final String RAW_PASSWORD = "RefreshTest#Secure99";

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<String> usernamesToCleanUp = new java.util.ArrayList<>();

    @AfterEach
    void cleanUpNonTransactionalUsers() {
        for (String username : usernamesToCleanUp) {
            jdbcTemplate.update(
                "delete from refresh_tokens where user_id in (select id from users where username = ?)", username);
            jdbcTemplate.update("delete from users where username = ?", username);
        }
        usernamesToCleanUp.clear();
    }

    private Long insertTestUser(String username, boolean enabled) {
        String hash = passwordEncoder.encode(RAW_PASSWORD);
        jdbcTemplate.update(
            "insert into users (group_id, username, email, password_hash, first_name, last_name, role, enabled) " +
                "values (1, ?, ?, ?, 'Refresh', 'Test', 'USER', ?)",
            username, username + "@example.com", hash, enabled);
        return jdbcTemplate.queryForObject("select id from users where username = ?", Long.class, username);
    }

    @Test
    @Transactional
    void refresh_validUnexpiredMatchingToken_returnsNewPairAndRotatesRowInPlace() {
        Long userId = insertTestUser("refresh_test_ok", true);
        TokenPairResponse loginResponse = authService.login(new LoginRequest("refresh_test_ok", RAW_PASSWORD));
        String hashBeforeRefresh = jdbcTemplate.queryForObject(
            "select token_hash from refresh_tokens where user_id = ?", String.class, userId);

        TokenPairResponse refreshed = authService.refresh(new RefreshRequest(loginResponse.refreshToken()));

        assertNotEquals(loginResponse.refreshToken(), refreshed.refreshToken());
        Integer rowCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId);
        assertEquals(1, rowCount);
        String hashAfterRefresh = jdbcTemplate.queryForObject(
            "select token_hash from refresh_tokens where user_id = ?", String.class, userId);
        assertNotEquals(hashBeforeRefresh, hashAfterRefresh);
        assertEquals(RefreshTokenGenerator.hash(refreshed.refreshToken()), hashAfterRefresh);
    }

    @Test
    @Transactional
    void refresh_preRotationTokenReplayedAfterRotation_rejectedAndRowDeleted_newestAlsoThenFails() {
        Long userId = insertTestUser("refresh_test_replay", true);
        TokenPairResponse loginResponse = authService.login(new LoginRequest("refresh_test_replay", RAW_PASSWORD));

        TokenPairResponse rotated = authService.refresh(new RefreshRequest(loginResponse.refreshToken()));

        assertThrows(InvalidRefreshTokenException.class,
            () -> authService.refresh(new RefreshRequest(loginResponse.refreshToken())));

        Integer rowCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId);
        assertEquals(0, rowCount);

        assertThrows(InvalidRefreshTokenException.class,
            () -> authService.refresh(new RefreshRequest(rotated.refreshToken())));
    }

    @Test
    @Transactional
    void refresh_expiredRefreshToken_rejectedAndNoRotationPerformed() {
        Long userId = insertTestUser("refresh_test_expired", true);
        TokenPairResponse loginResponse = authService.login(new LoginRequest("refresh_test_expired", RAW_PASSWORD));
        jdbcTemplate.update(
            "update refresh_tokens set expires_at = current_timestamp - interval '1 second' where user_id = ?",
            userId);
        String hashBefore = jdbcTemplate.queryForObject(
            "select token_hash from refresh_tokens where user_id = ?", String.class, userId);

        assertThrows(InvalidRefreshTokenException.class,
            () -> authService.refresh(new RefreshRequest(loginResponse.refreshToken())));

        String hashAfter = jdbcTemplate.queryForObject(
            "select token_hash from refresh_tokens where user_id = ?", String.class, userId);
        assertEquals(hashBefore, hashAfter);
    }

    @Test
    @Transactional
    void refresh_userDisabledSinceIssuance_rejectedAndNoRotationPerformed() {
        Long userId = insertTestUser("refresh_test_disabled", true);
        TokenPairResponse loginResponse = authService.login(new LoginRequest("refresh_test_disabled", RAW_PASSWORD));
        jdbcTemplate.update("update users set enabled = false where id = ?", userId);
        String hashBefore = jdbcTemplate.queryForObject(
            "select token_hash from refresh_tokens where user_id = ?", String.class, userId);

        assertThrows(InvalidRefreshTokenException.class,
            () -> authService.refresh(new RefreshRequest(loginResponse.refreshToken())));

        String hashAfter = jdbcTemplate.queryForObject(
            "select token_hash from refresh_tokens where user_id = ?", String.class, userId);
        assertEquals(hashBefore, hashAfter);
    }

    @Test
    @Transactional
    void refresh_garbageToken_rejectedWithoutExceptionLeakage() {
        assertThrows(InvalidRefreshTokenException.class,
            () -> authService.refresh(new RefreshRequest("this-was-never-issued-by-anyone")));
    }

    @Test
    void refresh_concurrentCallsWithSameToken_exactlyOneSucceeds() throws InterruptedException {
        String username = "refresh_test_concurrent";
        usernamesToCleanUp.add(username);
        insertTestUser(username, true);
        TokenPairResponse loginResponse = authService.login(new LoginRequest(username, RAW_PASSWORD));
        String rawRefreshToken = loginResponse.refreshToken();

        int callers = 2;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < callers; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    authService.refresh(new RefreshRequest(rawRefreshToken));
                    successCount.incrementAndGet();
                } catch (InvalidRefreshTokenException e) {
                    rejectedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(1, successCount.get());
        assertEquals(1, rejectedCount.get());
    }

    @Test
    @Transactional
    void logout_deletesTheUsersRefreshTokenRow() {
        Long userId = insertTestUser("logout_test_ok", true);
        authService.login(new LoginRequest("logout_test_ok", RAW_PASSWORD));

        authService.logout(userId);

        Integer rowCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId);
        assertEquals(0, rowCount);
    }

    @Test
    @Transactional
    void revoke_currentValidToken_deletesRow() {
        Long userId = insertTestUser("revoke_test_ok", true);
        TokenPairResponse loginResponse = authService.login(new LoginRequest("revoke_test_ok", RAW_PASSWORD));

        authService.revoke(loginResponse.refreshToken());

        Integer rowCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId);
        assertEquals(0, rowCount);
    }

    @Test
    @Transactional
    void revoke_unknownGarbageToken_isNoOpWithoutError() {
        authService.revoke("this-token-was-never-issued-by-anyone");
        // No exception thrown is the assertion - there is nothing to look up or clean up.
    }

    @Test
    @Transactional
    void revoke_alreadyRotatedAwayToken_isNoOp_currentSessionUntouched() {
        Long userId = insertTestUser("revoke_test_stale", true);
        TokenPairResponse loginResponse = authService.login(new LoginRequest("revoke_test_stale", RAW_PASSWORD));
        TokenPairResponse rotated = authService.refresh(new RefreshRequest(loginResponse.refreshToken()));

        // Revoke is deliberately narrow: it only deletes a row matching the *current* token_hash.
        // A pre-rotation token no longer matches anything (it now lives in previous_token_hash),
        // so revoking with it must not disturb the still-valid, rotated session.
        authService.revoke(loginResponse.refreshToken());

        String hashInDb = jdbcTemplate.queryForObject(
            "select token_hash from refresh_tokens where user_id = ?", String.class, userId);
        assertEquals(RefreshTokenGenerator.hash(rotated.refreshToken()), hashInDb);
    }

    @Test
    @Transactional
    void revoke_expiredButStillPresentToken_stillDeletesRow() {
        Long userId = insertTestUser("revoke_test_expired", true);
        TokenPairResponse loginResponse = authService.login(new LoginRequest("revoke_test_expired", RAW_PASSWORD));
        jdbcTemplate.update(
            "update refresh_tokens set expires_at = current_timestamp - interval '1 second' where user_id = ?",
            userId);

        authService.revoke(loginResponse.refreshToken());

        Integer rowCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId);
        assertEquals(0, rowCount);
    }

    @Test
    @Transactional
    void revoke_calledTwiceWithSameToken_secondCallIsHarmlessNoOp() {
        Long userId = insertTestUser("revoke_test_idempotent", true);
        TokenPairResponse loginResponse = authService.login(new LoginRequest("revoke_test_idempotent", RAW_PASSWORD));

        authService.revoke(loginResponse.refreshToken());
        authService.revoke(loginResponse.refreshToken());

        Integer rowCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_tokens where user_id = ?", Integer.class, userId);
        assertEquals(0, rowCount);
    }
}
