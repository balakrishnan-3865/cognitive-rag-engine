package com.skyshift.cognitiveragengine.auth.mapper;

import com.skyshift.cognitiveragengine.auth.model.entity.RefreshTokenEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RefreshTokenMapper {
    int upsertByUserId(RefreshTokenEntity refreshToken);
}
