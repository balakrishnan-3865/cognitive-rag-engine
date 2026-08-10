package com.skyshift.cognitiveragengine.auth.service;

import com.skyshift.cognitiveragengine.auth.config.JwtProperties;
import com.skyshift.cognitiveragengine.auth.exception.InvalidCredentialsException;
import com.skyshift.cognitiveragengine.auth.exception.InvalidRefreshTokenException;
import com.skyshift.cognitiveragengine.auth.jwt.JwtTokenProvider;
import com.skyshift.cognitiveragengine.auth.jwt.RefreshTokenGenerator;
import com.skyshift.cognitiveragengine.auth.mapper.RefreshTokenMapper;
import com.skyshift.cognitiveragengine.auth.model.dto.LoginRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.RefreshRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.TokenPairResponse;
import com.skyshift.cognitiveragengine.auth.model.entity.RefreshTokenEntity;
import com.skyshift.cognitiveragengine.user.mapper.UserMapper;
import com.skyshift.cognitiveragengine.user.model.AuthenticatedUser;
import com.skyshift.cognitiveragengine.user.model.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    private UserEntity enabledUserEntity(Long id) {
        return UserEntity.builder().id(id).username("jsmith").enabled(true).build();
    }

    @Test
    void refresh_validUnexpiredMatchingToken_returnsNewPairAndRotatesRowInPlace() {
        String rawToken = "valid-raw-refresh-token";
        String oldHash = RefreshTokenGenerator.hash(rawToken);
        RefreshTokenEntity row = RefreshTokenEntity.builder()
            .userId(1L)
            .tokenHash(oldHash)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
        when(refreshTokenMapper.selectByTokenHash(oldHash)).thenReturn(row);
        when(userMapper.selectById(1L)).thenReturn(enabledUserEntity(1L));
        when(refreshTokenMapper.rotateByTokenHash(eq(oldHash), anyString(), any(), any())).thenReturn(1);
        when(jwtTokenProvider.issueAccessToken("jsmith")).thenReturn("new-access-token");

        TokenPairResponse response = authService.refresh(new RefreshRequest(rawToken));

        assertEquals("new-access-token", response.accessToken());
        assertNotNull(response.refreshToken());
        verify(refreshTokenMapper, times(1)).rotateByTokenHash(eq(oldHash), anyString(), any(), any());
        verify(refreshTokenMapper, never()).deleteByPreviousTokenHash(any());
    }

    @Test
    void refresh_unknownToken_throwsAndNeverAttemptsRotation() {
        when(refreshTokenMapper.selectByTokenHash(anyString())).thenReturn(null);
        when(refreshTokenMapper.deleteByPreviousTokenHash(anyString())).thenReturn(0);

        assertThrows(InvalidRefreshTokenException.class,
            () -> authService.refresh(new RefreshRequest("garbage-token")));

        verify(refreshTokenMapper, never()).rotateByTokenHash(any(), any(), any(), any());
    }

    @Test
    void refresh_expiredToken_throwsAndNoRotationPerformed() {
        String rawToken = "expired-raw-refresh-token";
        String oldHash = RefreshTokenGenerator.hash(rawToken);
        RefreshTokenEntity row = RefreshTokenEntity.builder()
            .userId(1L)
            .tokenHash(oldHash)
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .build();
        when(refreshTokenMapper.selectByTokenHash(oldHash)).thenReturn(row);

        assertThrows(InvalidRefreshTokenException.class,
            () -> authService.refresh(new RefreshRequest(rawToken)));

        verify(refreshTokenMapper, never()).rotateByTokenHash(any(), any(), any(), any());
        verify(userMapper, never()).selectById(any());
    }

    @Test
    void refresh_userDisabledSinceIssuance_throwsAndNoRotationPerformed() {
        String rawToken = "disabled-user-raw-refresh-token";
        String oldHash = RefreshTokenGenerator.hash(rawToken);
        RefreshTokenEntity row = RefreshTokenEntity.builder()
            .userId(2L)
            .tokenHash(oldHash)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
        when(refreshTokenMapper.selectByTokenHash(oldHash)).thenReturn(row);
        when(userMapper.selectById(2L)).thenReturn(
            UserEntity.builder().id(2L).username("mgarcia").enabled(false).build());

        assertThrows(InvalidRefreshTokenException.class,
            () -> authService.refresh(new RefreshRequest(rawToken)));

        verify(refreshTokenMapper, never()).rotateByTokenHash(any(), any(), any(), any());
    }

    @Test
    void refresh_rotationLosesRace_throwsAndAttemptsReuseCleanup() {
        String rawToken = "raced-raw-refresh-token";
        String oldHash = RefreshTokenGenerator.hash(rawToken);
        RefreshTokenEntity row = RefreshTokenEntity.builder()
            .userId(1L)
            .tokenHash(oldHash)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
        when(refreshTokenMapper.selectByTokenHash(oldHash)).thenReturn(row);
        when(userMapper.selectById(1L)).thenReturn(enabledUserEntity(1L));
        when(refreshTokenMapper.rotateByTokenHash(eq(oldHash), anyString(), any(), any())).thenReturn(0);

        assertThrows(InvalidRefreshTokenException.class,
            () -> authService.refresh(new RefreshRequest(rawToken)));

        verify(refreshTokenMapper, times(1)).deleteByPreviousTokenHash(oldHash);
    }

    @Test
    void logout_deletesRefreshTokenRowForGivenUser() {
        authService.logout(5L);

        verify(refreshTokenMapper, times(1)).deleteByUserId(5L);
    }

    @Test
    void revoke_hashesPresentedTokenAndDeletesByThatHash() {
        String rawToken = "some-raw-refresh-token";

        authService.revoke(rawToken);

        verify(refreshTokenMapper, times(1)).deleteByTokenHash(RefreshTokenGenerator.hash(rawToken));
    }
}
