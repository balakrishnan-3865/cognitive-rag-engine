package com.skyshift.cognitiveragengine.qa.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.qa.model.KnowledgeSourceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KnowledgeSourceResponseConverter {

    private final ObjectMapper mapper;

    public KnowledgeSourceResponseConverter() {
        this.mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public KnowledgeSourceResponse convertRawResponse(String rawJsonString) throws Exception {
        try {
            JsonNode rootNode = mapper.readTree(rawJsonString);
            String answer = rootNode.has("answer")
                ? rootNode.get("answer").asText("")
                : "";
            return new KnowledgeSourceResponse(true, answer);
        } catch (Exception e) {
            log.warn("Lenient JSON parsing failed, falling back to string extraction: {}", e.getMessage());
            return extractViaString(rawJsonString);
        }
    }

    private KnowledgeSourceResponse extractViaString(String rawString) throws Exception {
        int answerPos = rawString.indexOf("\"answer\"");
        int colonPos = rawString.indexOf(":", answerPos);
        int quoteStart = rawString.indexOf("\"", colonPos);

        if (answerPos < 0 || quoteStart < 0) {
            throw new IllegalArgumentException("Cannot find 'answer' field in response");
        }

        StringBuilder answer = new StringBuilder();
        int idx = quoteStart + 1;

        while (idx < rawString.length()) {
            char c = rawString.charAt(idx);
            if (c == '\\' && idx + 1 < rawString.length()) {
                idx++;
                switch (rawString.charAt(idx)) {
                    case 'n' -> answer.append('\n');
                    case 't' -> answer.append('\t');
                    case 'r' -> answer.append('\r');
                    case '"' -> answer.append('"');
                    case '\\' -> answer.append('\\');
                    default -> answer.append(rawString.charAt(idx));
                }
            } else if (c == '"') {
                return new KnowledgeSourceResponse(true, answer.toString().trim());
            } else {
                answer.append(c);
            }
            idx++;
        }

        throw new IllegalArgumentException("Malformed answer field");
    }
}
