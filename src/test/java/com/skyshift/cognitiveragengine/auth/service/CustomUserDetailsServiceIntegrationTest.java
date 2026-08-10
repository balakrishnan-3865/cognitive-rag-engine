package com.skyshift.cognitiveragengine.auth.service;

import com.skyshift.cognitiveragengine.user.model.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomUserDetailsServiceIntegrationTest {

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void loadUserByUsername_returnsCorrectlyPopulatedAuthenticatedUser() {
        AuthenticatedUser user = (AuthenticatedUser) customUserDetailsService.loadUserByUsername("jsmith");

        assertEquals("jsmith", user.getUsername());
        assertNotNull(user.getId());
        assertEquals(1L, user.getGroupId());
        assertEquals("USER", user.getRole());
        assertTrue(user.isEnabled());
    }

    @Test
    void loadUserByUsername_unknownUsername_throwsUsernameNotFoundException() {
        assertThrows(UsernameNotFoundException.class, () ->
            customUserDetailsService.loadUserByUsername("does-not-exist"));
    }

    @Test
    void isEnabled_falseForDisabledSeedUser() {
        AuthenticatedUser user = (AuthenticatedUser) customUserDetailsService.loadUserByUsername("mgarcia");

        assertFalse(user.isEnabled());
    }

    @Test
    void isEnabled_trueForEnabledSeedUser() {
        AuthenticatedUser user = (AuthenticatedUser) customUserDetailsService.loadUserByUsername("rkumar");

        assertTrue(user.isEnabled());
    }
}
