package com.skyshift.cognitiveragengine.ingestion.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

// Null fields (e.g. sectionPath before the first header) are omitted rather than serialized as
// null — downstream consumers like VectorIngestionService merge this JSON directly into a
// Document's metadata map, which rejects null values outright.
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Builder
public class ChunkMetadata {

    private String documentName;
    private Integer pageNumber;
    private Integer tokenCount;
    private Integer length;
    private Boolean hasOverlap;
    private String chunkStrategy;
    private Integer chunkIndex;
    private Long documentId;
    private Long groupId;

    // Docling structural chunks (Phase 7) can span pages, so pageNumber (single page, valid only
    // for the retired one-PDFBox-Document-per-page path) becomes a range here.
    private Integer pageStart;
    private Integer pageEnd;
    private String sectionPath;
    private String itemType;

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