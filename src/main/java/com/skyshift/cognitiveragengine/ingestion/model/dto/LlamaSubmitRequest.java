package com.skyshift.cognitiveragengine.ingestion.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlamaSubmitRequest(
        @JsonProperty("file_id") String fileId,
        String tier,
        String version
) {}
