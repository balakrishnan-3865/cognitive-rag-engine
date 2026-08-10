package com.skyshift.cognitiveragengine.auth.service;

import com.skyshift.cognitiveragengine.auth.config.JwtProperties;
import com.skyshift.cognitiveragengine.auth.exception.InvalidCredentialsException;
import com.skyshift.cognitiveragengine.auth.jwt.JwtTokenProvider;
import com.skyshift.cognitiveragengine.auth.mapper.RefreshTokenMapper;
import com.skyshift.cognitiveragengine.auth.model.dto.LoginRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.TokenPairResponse;
import com.skyshift.cognitiveragengine.user.mapper.UserMapper;
import com.skyshift.cognitiveragengine.user.model.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private CustomUserDetailsService userDetailsService;
    private UserMapper userMapper;
    private RefreshTokenMapper refreshTokenMapper;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userDetailsService = mock(CustomUserDetailsService.class);
        userMapper = mock(UserMapper.class);
        refreshTokenMapper = mock(RefreshTokenMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        JwtProperties jwtProperties = new JwtProperties("unit-test-secret", 900, 604800);

        authService = new AuthService(
            userDetailsService, userMapper, refreshTokenMapper, passwordEncoder, jwtTokenProvider, jwtProperties);
    }

    private AuthenticatedUser enabledUser() {
        return new AuthenticatedUser(1L, 1L, "jsmith", "hashed-pw", "USER", true);
    }

    @Test
    void login_correctCredentials_returnsTokenPairAndPersistsRefreshToken() {
        AuthenticatedUser user = enabledUser();
        when(userDetailsService.loadUserByUsername("jsmith")).thenReturn(user);
        when(passwordEncoder.matches("correct-password", "hashed-pw")).thenReturn(true);
        when(jwtTokenProvider.issueAccessToken("jsmith")).thenReturn("access-token-value");

        TokenPairResponse response = authService.login(new LoginRequest("jsmith", "correct-password"));

        assertEquals("access-token-value", response.accessToken());
        assertNotNull(response.refreshToken());
        verify(refreshTokenMapper, times(1)).upsertByUserId(any());
        verify(userMapper, times(1)).updateLastLoginAt(eq(1L), any());
    }

    @Test
    void login_wrongPassword_throwsAndIssuesNoTokensOrRows() {
        AuthenticatedUser user = enabledUser();
        when(userDetailsService.loadUserByUsername("jsmith")).thenReturn(user);
        when(passwordEncoder.matches("wrong-password", "hashed-pw")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
            () -> authService.login(new LoginRequest("jsmith", "wrong-password")));

        verify(refreshTokenMapper, never()).upsertByUserId(any());
        verify(userMapper, never()).updateLastLoginAt(anyLong(), any());
    }

    @Test
    void login_unknownUsername_throwsSameExceptionAsWrongPassword() {
        when(userDetailsService.loadUserByUsername("ghost"))
            .thenThrow(new UsernameNotFoundException("User not found: ghost"));

        assertThrows(InvalidCredentialsException.class,
            () -> authService.login(new LoginRequest("ghost", "whatever-password")));

        verify(refreshTokenMapper, never()).upsertByUserId(any());
    }

    @Test
    void login_disabledUserWithCorrectPassword_throwsAndIssuesNoTokens() {
        AuthenticatedUser disabledUser = new AuthenticatedUser(2L, 1L, "mgarcia", "hashed-pw", "USER", false);
        when(userDetailsService.loadUserByUsername("mgarcia")).thenReturn(disabledUser);
        when(passwordEncoder.matches("correct-password", "hashed-pw")).thenReturn(true);

        assertThrows(InvalidCredentialsException.class,
            () -> authService.login(new LoginRequest("mgarcia", "correct-password")));

        verify(refreshTokenMapper, never()).upsertByUserId(any());
        verify(userMapper, never()).updateLastLoginAt(anyLong(), any());
    }
}
