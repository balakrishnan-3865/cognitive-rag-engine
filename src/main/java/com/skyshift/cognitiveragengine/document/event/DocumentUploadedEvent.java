package com.skyshift.cognitiveragengine.document.event;

/**
 * Domain event published when a document is successfully uploaded.
 */
public record DocumentUploadedEvent(
    Long documentId,
    Long groupId
) {}