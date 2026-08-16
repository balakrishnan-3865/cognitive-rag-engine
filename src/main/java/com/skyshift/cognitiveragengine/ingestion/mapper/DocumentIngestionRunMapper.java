package com.skyshift.cognitiveragengine.ingestion.mapper;

import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentIngestionRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentIngestionRunMapper {
    int insert(DocumentIngestionRunEntity run);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int deleteById(@Param("id") Long id);

    DocumentIngestionRunEntity selectById(@Param("id") Long id);
}
