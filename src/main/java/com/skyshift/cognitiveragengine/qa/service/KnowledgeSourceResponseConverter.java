package com.skyshift.cognitiveragengine.qa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.qa.model.KnowledgeSourceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KnowledgeSourceResponseConverter {

    private final ObjectMapper mapper;

    public KnowledgeSourceResponseConverter(@Qualifier("llmParser") ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public KnowledgeSourceResponse convertRawResponse(String rawJsonString) throws Exception {
        try {
            return mapper.readValue(rawJsonString, KnowledgeSourceResponse.class);
        } catch (Exception e) {
            log.warn("LLM response parsing failed: {}", e.getMessage());
            return new KnowledgeSourceResponse(false, "");
        }
    }
}
