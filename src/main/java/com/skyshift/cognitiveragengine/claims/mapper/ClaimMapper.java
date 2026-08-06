package com.skyshift.cognitiveragengine.claims.mapper;

import com.skyshift.cognitiveragengine.claims.model.entity.ClaimEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ClaimMapper {
    List<ClaimEntity> findByUserIdAndGroupIdSince(
        @Param("userId") Long userId,
        @Param("groupId") Long groupId,
        @Param("fromDate") LocalDate fromDate);
}
