package com.skyshift.cognitiveragengine.classifier.config;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression guard for the observability gap found during ChatClient structured-output
 * planning: {@link OpenAiChatModel.Builder} defaults {@code observationRegistry} to
 * {@link ObservationRegistry#NOOP}, so a hand-built {@link ChatModel} bean that never calls
 * {@code .observationRegistry(...)} silently produces zero OTel spans/metrics.
 */
class IntentClassifierConfigurationTest {

    private final IntentClassifierConfiguration configuration = new IntentClassifierConfiguration();

    @Test
    void intentClassificationChatModel_usesInjectedObservationRegistry_notNoop() {
        IntentClassifierModelProperties properties = new IntentClassifierModelProperties(
                "openai/gpt-oss-20b",
                "test-api-key",
                "https://api.groq.com/openai/v1",
                new IntentClassifierModelProperties.Options(0.1, 200)
        );
        ObservationRegistry observationRegistry = ObservationRegistry.create();

        ChatModel chatModel = configuration.intentClassificationChatModel(properties, observationRegistry);

        Object actualRegistry = ReflectionTestUtils.getField(chatModel, "observationRegistry");
        assertNotNull(actualRegistry);
        assertNotSame(ObservationRegistry.NOOP, actualRegistry);
        assertSame(observationRegistry, actualRegistry);
    }
}
