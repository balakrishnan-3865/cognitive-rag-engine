package com.skyshift.cognitiveragengine.ingestion.mapper;

import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

@Mapper
public interface DocumentChunkMapper {
    int insert(DocumentChunkEntity chunk);

    int batchInsert(List<DocumentChunkEntity> chunks);

    int deleteByDocumentId(Long documentId);

    List<DocumentChunkEntity> selectByDocumentId(Long documentId);
}