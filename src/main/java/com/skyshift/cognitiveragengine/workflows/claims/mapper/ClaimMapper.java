package com.skyshift.cognitiveragengine.workflows.claims.mapper;

import com.skyshift.cognitiveragengine.workflows.claims.model.entity.ClaimEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ClaimMapper {
    List<ClaimEntity> findByUserIdAndGroupIdBetween(
        @Param("userId") Long userId,
        @Param("groupId") Long groupId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate);
}
