package com.skyshift.cognitiveragengine.common.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Extends Spring AI's default vector-store observation convention to attach a compact summary
 * of the retrieved candidates (chunkId + similarity score) onto the pg_vector query span, using
 * the vendor-neutral OTel attribute db.vector.query.response.documents. Spring AI's M1 build
 * populates VectorStoreObservationContext.getQueryResponse() internally but never surfaces it -
 * deliberately kept to id+score rather than full chunk text: dense search alone returns up to
 * top_k candidates per request, and only a handful ever reach the LLM, so full-text capture here
 * would mostly duplicate what the tool_call/gen_ai.prompt spans already carry for the chunks that
 * actually mattered.
 */
@Slf4j
@Component
public class VectorStoreContentObservationConvention extends DefaultVectorStoreObservationConvention {

    private final ObjectMapper objectMapper;
    private final ObservabilityProperties properties;

    public VectorStoreContentObservationConvention(ObjectMapper objectMapper, ObservabilityProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(VectorStoreObservationContext context) {
        KeyValues keyValues = super.getHighCardinalityKeyValues(context);
        if (!properties.isCaptureContent() || context.getQueryResponse() == null) {
            return keyValues;
        }

        String summary = serializeQueryResponse(context.getQueryResponse());
        if (summary != null) {
            keyValues = keyValues.and(KeyValue.of("db.vector.query.response.documents", summary));
        }
        return keyValues;
    }

    private String serializeQueryResponse(List<Document> documents) {
        try {
            List<Map<String, Object>> summary = documents.stream()
                    .map(this::toChunkSummary)
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            log.debug("Failed to serialize vector store query response for tracing: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> toChunkSummary(Document document) {
        // LinkedHashMap, not Map.of: score can be null, and Map.of throws NPE on a null value.
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("chunkId", String.valueOf(document.getMetadata().get("chunkId")));
        map.put("score", document.getScore());
        return map;
    }
}