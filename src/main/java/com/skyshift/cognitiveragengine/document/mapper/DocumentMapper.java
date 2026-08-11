package com.skyshift.cognitiveragengine.document.mapper;

import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentMapper {
    int insert(DocumentEntity document);

    DocumentEntity selectByIdAndGroupId(Long documentId, Long groupId);

    DocumentEntity selectById(@Param("documentId") Long documentId);

    List<DocumentEntity> findByStatus(@Param("status") String status);

    int updateStatus(@Param("documentId") Long documentId, @Param("status") String status);

    int updateStatusAndReason(
        @Param("documentId") Long documentId,
        @Param("status") String status,
        @Param("failureReason") String failureReason);

    int updateStatusFromTo(
        @Param("documentId") Long documentId,
        @Param("fromStatus") String fromStatus,
        @Param("toStatus") String toStatus);

    int flipCurrentVersion(@Param("fromId") Long fromId, @Param("toId") Long toId);

    List<Long> findCurrentReadyDocumentIds(@Param("groupId") Long groupId);

    DocumentEntity findCurrentVersionInLineage(
        @Param("rootDocumentId") Long rootDocumentId,
        @Param("excludeId") Long excludeId);

    List<DocumentEntity> selectCurrentVersionsByGroupIdAndUserId(
        @Param("groupId") Long groupId,
        @Param("userId") Long userId);
}
