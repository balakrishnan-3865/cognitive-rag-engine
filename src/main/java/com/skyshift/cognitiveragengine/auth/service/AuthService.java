package com.skyshift.cognitiveragengine.auth.service;

import com.skyshift.cognitiveragengine.auth.config.JwtProperties;
import com.skyshift.cognitiveragengine.auth.exception.InvalidCredentialsException;
import com.skyshift.cognitiveragengine.auth.jwt.JwtTokenProvider;
import com.skyshift.cognitiveragengine.auth.jwt.RefreshTokenGenerator;
import com.skyshift.cognitiveragengine.auth.mapper.RefreshTokenMapper;
import com.skyshift.cognitiveragengine.auth.exception.InvalidRefreshTokenException;
import com.skyshift.cognitiveragengine.auth.model.dto.LoginRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.RefreshRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.TokenPairResponse;
import com.skyshift.cognitiveragengine.auth.model.entity.RefreshTokenEntity;
import com.skyshift.cognitiveragengine.user.mapper.UserMapper;
import com.skyshift.cognitiveragengine.user.model.AuthenticatedUser;
import com.skyshift.cognitiveragengine.user.model.entity.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid username or password";
    private static final String INVALID_REFRESH_TOKEN_MESSAGE = "Invalid or expired refresh token";

    private final CustomUserDetailsService userDetailsService;
    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    public AuthService(
        CustomUserDetailsService userDetailsService,
        UserMapper userMapper,
        RefreshTokenMapper refreshTokenMapper,
        PasswordEncoder passwordEncoder,
        JwtTokenProvider jwtTokenProvider,
        JwtProperties jwtProperties
    ) {
        this.userDetailsService = userDetailsService;
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public TokenPairResponse login(LoginRequest request) {
        AuthenticatedUser user;
        try {
            user = (AuthenticatedUser) userDetailsService.loadUserByUsername(request.username());
        } catch (UsernameNotFoundException e) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword()) || !user.isEnabled()) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        String accessToken = jwtTokenProvider.issueAccessToken(user.getUsername());
        String rawRefreshToken = RefreshTokenGenerator.generate();

        LocalDateTime now = LocalDateTime.now();
        refreshTokenMapper.upsertByUserId(RefreshTokenEntity.builder()
            .userId(user.getId())
            .tokenHash(RefreshTokenGenerator.hash(rawRefreshToken))
            .expiresAt(now.plusSeconds(jwtProperties.refreshTokenTtlSeconds()))
            .createdAt(now)
            .updatedAt(now)
            .build());

        userMapper.updateLastLoginAt(user.getId(), now);

        return new TokenPairResponse(accessToken, rawRefreshToken, "Bearer", jwtProperties.accessTokenTtlSeconds());
    }

    @Transactional
    public TokenPairResponse refresh(RefreshRequest request) {
        String oldHash = RefreshTokenGenerator.hash(request.refreshToken());
        RefreshTokenEntity current = refreshTokenMapper.selectByTokenHash(oldHash);

        if (current == null) {
            throw rejectAsPossibleReuse(oldHash);
        }

        LocalDateTime now = LocalDateTime.now();
        if (current.getExpiresAt().isBefore(now)) {
            throw new InvalidRefreshTokenException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        UserEntity user = userMapper.selectById(current.getUserId());
        if (user == null || !user.getEnabled()) {
            throw new InvalidRefreshTokenException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        String newRawRefreshToken = RefreshTokenGenerator.generate();
        String newHash = RefreshTokenGenerator.hash(newRawRefreshToken);
        LocalDateTime newExpiresAt = now.plusSeconds(jwtProperties.refreshTokenTtlSeconds());

        int rowsUpdated = refreshTokenMapper.rotateByTokenHash(oldHash, newHash, newExpiresAt, now);
        if (rowsUpdated == 0) {
            throw rejectAsPossibleReuse(oldHash);
        }

        String accessToken = jwtTokenProvider.issueAccessToken(user.getUsername());
        return new TokenPairResponse(accessToken, newRawRefreshToken, "Bearer", jwtProperties.accessTokenTtlSeconds());
    }

    // A lookup miss on the current token_hash column means either a garbage token or a replay of
    // a token this row already rotated away from - both look identical from here. If it matches
    // previous_token_hash it's the latter, so the whole row (and therefore the session) is deleted
    // rather than left standing, since the caller holding a stale token is unable to log in again
    // silently with it and only a real re-login recovers.
    private InvalidRefreshTokenException rejectAsPossibleReuse(String hash) {
        int deleted = refreshTokenMapper.deleteByPreviousTokenHash(hash);
        if (deleted > 0) {
            log.warn("Refresh token reuse detected - session revoked");
        }
        return new InvalidRefreshTokenException(INVALID_REFRESH_TOKEN_MESSAGE);
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenMapper.deleteByUserId(userId);
    }

    // Unconditional delete, no expiry check, no distinction in outcome between "matched and
    // deleted" and "matched nothing" - the caller must not be able to use this endpoint's
    // response to probe whether a given token string was ever a real, currently-live token.
    @Transactional
    public void revoke(String rawRefreshToken) {
        refreshTokenMapper.deleteByTokenHash(RefreshTokenGenerator.hash(rawRefreshToken));
    }
}
