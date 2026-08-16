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
public class DocumentIngestionRunEntity {
    private Long id;
    private Long documentId;
    private String doclingTaskId;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
