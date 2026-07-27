package com.skyshift.cognitiveragengine.ingestion.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentChunkEntity {
    private Long id;
    private Long groupId;
    private Long documentId;
    private Integer chunkNumber;
    private String chunkText;
    private String metadataJson;
    private Integer startPosition;
    private Integer endPosition;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}