package com.skyshift.cognitiveragengine.ingestion.model.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChunkMetadata {

    private Integer pageNumber;
    private Integer tokenCount;
    private String source;
    private String chunkStrategy;
    private Integer chunkIndex;
    private Long documentId;
    private Long groupId;

    public String toJson() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        try {
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ChunkMetadata", e);
        }
    }

    public static ChunkMetadata fromJson(String json) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        try {
            return mapper.readValue(json, ChunkMetadata.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize ChunkMetadata", e);
        }
    }
}