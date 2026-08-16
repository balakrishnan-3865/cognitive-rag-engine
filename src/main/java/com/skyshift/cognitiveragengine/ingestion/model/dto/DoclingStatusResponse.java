package com.skyshift.cognitiveragengine.ingestion.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire shape confirmed live in Phase 0 (docs/overview/docling-parsing/DOCLING_PHASE_COMPLETION.md,
 * Section 0.3/0.4): status values are lowercase pending/started/success/failure. The field name
 * itself ("task_status") follows docling-serve's published schema; not independently re-verified
 * against the running sidecar for this phase — revisit if a live poll ever comes back null.
 */
public record DoclingStatusResponse(
        @JsonProperty("task_status") String taskStatus
) {}
