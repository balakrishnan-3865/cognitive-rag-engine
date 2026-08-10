package com.skyshift.cognitiveragengine.auth.service;

import com.skyshift.cognitiveragengine.auth.config.JwtProperties;
import com.skyshift.cognitiveragengine.auth.exception.InvalidCredentialsException;
import com.skyshift.cognitiveragengine.auth.jwt.JwtTokenProvider;
import com.skyshift.cognitiveragengine.auth.jwt.RefreshTokenGenerator;
import com.skyshift.cognitiveragengine.auth.mapper.RefreshTokenMapper;
import com.skyshift.cognitiveragengine.auth.model.dto.LoginRequest;
import com.skyshift.cognitiveragengine.auth.model.dto.TokenPairResponse;
import com.skyshift.cognitiveragengine.auth.model.entity.RefreshTokenEntity;
import com.skyshift.cognitiveragengine.user.mapper.UserMapper;
import com.skyshift.cognitiveragengine.user.model.AuthenticatedUser;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid username or password";

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
}
