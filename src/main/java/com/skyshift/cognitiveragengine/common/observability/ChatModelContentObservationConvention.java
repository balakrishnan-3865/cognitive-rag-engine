package com.skyshift.cognitiveragengine.common.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Extends Spring AI's default chat-model observation convention to attach the raw prompt and
 * completion text onto the exported span, using the vendor-neutral OTel GenAI semantic convention
 * attributes (gen_ai.prompt / gen_ai.completion) rather than any backend-specific namespace - the
 * span is published to OpenTelemetry only, so any conformant consumer (Langfuse today, TruLens or
 * others tomorrow) can read it without app-side reconfiguration. Needed for offline groundedness
 * evaluation against a hand-labeled golden set - Spring AI's built-in prompt/completion handlers
 * only log via SLF4J, they don't attach content to the span itself.
 */
@Slf4j
@Component
public class ChatModelContentObservationConvention extends DefaultChatModelObservationConvention {

    private final ObjectMapper objectMapper;
    private final ObservabilityProperties properties;

    public ChatModelContentObservationConvention(ObjectMapper objectMapper, ObservabilityProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ChatModelObservationContext context) {
        KeyValues keyValues = super.getHighCardinalityKeyValues(context);
        if (!properties.isCaptureContent()) {
            return keyValues;
        }

        String input = serializePrompt(context.getRequest());
        if (input != null) {
            keyValues = keyValues.and(KeyValue.of("gen_ai.prompt", input));
        }

        String output = serializeCompletion(context.getResponse());
        if (output != null) {
            keyValues = keyValues.and(KeyValue.of("gen_ai.completion", output));
        }

        return keyValues;
    }

    private String serializePrompt(Prompt prompt) {
        if (prompt == null) {
            return null;
        }
        try {
            List<Map<String, String>> messages = prompt.getInstructions().stream()
                    .map(this::toMessageMap)
                    .collect(Collectors.toList());
            return objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            log.debug("Failed to serialize prompt for tracing: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> toMessageMap(Message message) {
        // LinkedHashMap, not Map.of: content can be null (tool-call-only assistant messages),
        // and Map.of throws NPE on a null value.
        Map<String, String> map = new LinkedHashMap<>();
        map.put("role", message.getMessageType().getValue());
        map.put("content", resolveContent(message));
        return map;
    }

    /**
     * getText() doesn't carry the real payload for these two message types: a tool-call
     * AssistantMessage stores the call in getToolCalls(), and ToolResponseMessage hardcodes
     * getText() to "" (its content lives in getResponses()) - see ToolResponseMessage's
     * constructor in spring-ai-model.
     */
    private String resolveContent(Message message) {
        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            return assistantMessage.getToolCalls().stream()
                    .map(toolCall -> "%s(%s)".formatted(toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining(", "));
        }
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            return toolResponseMessage.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .collect(Collectors.joining("\n\n"));
        }
        return message.getText();
    }

    private String serializeCompletion(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(response.getResult().getOutput().getText());
        } catch (Exception e) {
            log.debug("Failed to serialize completion for tracing: {}", e.getMessage());
            return null;
        }
    }
}