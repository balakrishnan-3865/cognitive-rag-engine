package com.skyshift.cognitiveragengine.ingestion.event;

public record DocumentChunksCreatedEvent(Long documentId, Long groupId) {
}