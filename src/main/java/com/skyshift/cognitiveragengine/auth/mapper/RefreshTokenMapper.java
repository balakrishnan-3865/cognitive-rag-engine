package com.skyshift.cognitiveragengine.auth.mapper;

import com.skyshift.cognitiveragengine.auth.model.entity.RefreshTokenEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface RefreshTokenMapper {
    int upsertByUserId(RefreshTokenEntity refreshToken);

    RefreshTokenEntity selectByTokenHash(@Param("tokenHash") String tokenHash);

    int rotateByTokenHash(
        @Param("oldTokenHash") String oldTokenHash,
        @Param("newTokenHash") String newTokenHash,
        @Param("expiresAt") LocalDateTime expiresAt,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    int deleteByPreviousTokenHash(@Param("previousTokenHash") String previousTokenHash);

    int deleteByUserId(@Param("userId") Long userId);

    int deleteByTokenHash(@Param("tokenHash") String tokenHash);
}
