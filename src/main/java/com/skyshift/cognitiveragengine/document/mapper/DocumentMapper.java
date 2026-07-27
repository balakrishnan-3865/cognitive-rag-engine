package com.skyshift.cognitiveragengine.document.mapper;

import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentMapper {
    int insert(DocumentEntity document);

    DocumentEntity selectByIdAndGroupId(Long documentId, Long groupId);
}
