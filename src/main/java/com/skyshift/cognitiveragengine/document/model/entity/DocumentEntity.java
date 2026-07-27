package com.skyshift.cognitiveragengine.document.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentEntity {
    private Long id;
    private Long groupId;
    private Long uploadedUserId;
    private String fileName;
    private String fileExtension;
    private String contextType;
    private Long fileSize;
    private String fileHash;
    private String storageBucket;
    private String storageObjectKey;
    private String status;
    private Boolean deleted;
    private String failureReason;
    private LocalDateTime uploadedAt;
    private LocalDateTime processedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
