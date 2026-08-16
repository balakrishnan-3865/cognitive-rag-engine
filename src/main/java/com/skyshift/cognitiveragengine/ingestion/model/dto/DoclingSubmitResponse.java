package com.skyshift.cognitiveragengine.ingestion.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DoclingSubmitResponse(
        @JsonProperty("task_id") String taskId
) {}
