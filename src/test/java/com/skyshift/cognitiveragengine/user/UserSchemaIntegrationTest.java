package com.skyshift.cognitiveragengine.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Schema-level tests for the Phase 1 migrations (users.role, users.last_login_at, refresh_tokens).
 * A failing migration would also fail context startup for every other @SpringBootTest in the
 * module, so successful context load here is itself proof the migrations applied cleanly.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void usersTable_roleColumn_defaultsToUser() {
        jdbcTemplate.update(
            "insert into users (group_id, username, email, password_hash, first_name, last_name) " +
                "values (1, 'schema_test_default_role', 'schema_test_default_role@example.com', 'hash', 'Schema', 'Test')"
        );

        String role = jdbcTemplate.queryForObject(
            "select role from users where username = 'schema_test_default_role'", String.class);

        assertEquals("USER", role);
    }

    @Test
    void usersTable_roleColumn_rejectsInvalidValueViaCheckConstraint() {
        assertThrows(DataIntegrityViolationException.class, () ->
            jdbcTemplate.update(
                "insert into users (group_id, username, email, password_hash, first_name, last_name, role) " +
                    "values (1, 'schema_test_bad_role', 'schema_test_bad_role@example.com', 'hash', 'Schema', 'Test', 'SUPERADMIN')"
            ));
    }

    @Test
    void usersTable_hasLastLoginAtColumn() {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns " +
                "where table_name = 'users' and column_name = 'last_login_at'",
            Integer.class);

        assertEquals(1, count);
    }

    @Test
    void refreshTokensTable_enforcesUniqueUserId() {
        Long existingUserId = jdbcTemplate.queryForObject(
            "select id from users where username = 'jsmith'", Long.class);

        jdbcTemplate.update(
            "insert into refresh_tokens (user_id, token_hash, expires_at) " +
                "values (?, 'hash-1', current_timestamp + interval '1 day')",
            existingUserId);

        assertThrows(DataIntegrityViolationException.class, () ->
            jdbcTemplate.update(
                "insert into refresh_tokens (user_id, token_hash, expires_at) " +
                    "values (?, 'hash-2', current_timestamp + interval '1 day')",
                existingUserId));
    }
}
