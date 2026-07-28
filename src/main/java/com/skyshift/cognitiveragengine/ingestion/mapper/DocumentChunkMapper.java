package com.skyshift.cognitiveragengine.ingestion.mapper;

import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface DocumentChunkMapper {
    int insert(DocumentChunkEntity chunk);

    int batchInsert(List<DocumentChunkEntity> chunks);

    int batchInsertChunks(@Param("chunks") List<DocumentChunkEntity> chunks);

    int deleteByDocumentId(Long documentId);

    int deleteByDocumentIdAndGroupId(
        @Param("documentId") Long documentId,
        @Param("groupId") Long groupId);

    List<DocumentChunkEntity> selectByDocumentId(Long documentId);

    List<DocumentChunkEntity> selectByDocumentIdAndGroupId(
        @Param("documentId") Long documentId,
        @Param("groupId") Long groupId);

    List<DocumentChunkEntity> selectByDocumentIdAndGroupIdWithPagination(
        @Param("documentId") Long documentId,
        @Param("groupId") Long groupId,
        @Param("offset") int offset,
        @Param("limit") int limit);
}