package com.skyshift.cognitiveragengine.user.service;

import com.skyshift.cognitiveragengine.auth.exception.DuplicateUserException;
import com.skyshift.cognitiveragengine.auth.model.dto.RegisterRequest;
import com.skyshift.cognitiveragengine.user.mapper.UserMapper;
import com.skyshift.cognitiveragengine.user.model.dto.UserSummaryResponse;
import com.skyshift.cognitiveragengine.user.model.entity.UserEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserSummaryResponse register(RegisterRequest request) {
        if (userMapper.existsByUsername(request.username())) {
            throw new DuplicateUserException("Username already exists: " + request.username());
        }
        if (userMapper.existsByEmail(request.email())) {
            throw new DuplicateUserException("Email already exists: " + request.email());
        }

        UserEntity entity = UserEntity.builder()
            .groupId(request.groupId())
            .username(request.username())
            .email(request.email())
            .passwordHash(passwordEncoder.encode(request.password()))
            .firstName(request.firstName())
            .lastName(request.lastName())
            .role("USER")
            .enabled(true)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        try {
            userMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // Pre-check above closes the common case; this catches the race where two
            // requests for the same username/email pass the pre-check concurrently.
            log.warn("Duplicate registration race detected for username={}", request.username());
            throw new DuplicateUserException("Username or email already exists");
        }

        return toSummary(entity);
    }

    public UserSummaryResponse getCurrentUser(Long userId) {
        UserEntity entity = userMapper.selectById(userId);
        return toSummary(entity);
    }

    private UserSummaryResponse toSummary(UserEntity entity) {
        return new UserSummaryResponse(
            entity.getId(),
            entity.getGroupId(),
            entity.getUsername(),
            entity.getEmail(),
            entity.getFirstName(),
            entity.getLastName(),
            entity.getRole(),
            entity.getEnabled()
        );
    }
}
