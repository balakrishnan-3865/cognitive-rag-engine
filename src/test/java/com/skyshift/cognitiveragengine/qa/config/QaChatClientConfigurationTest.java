package com.skyshift.cognitiveragengine.qa.config;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.mock;

/**
 * Regression guard for the mechanism found during ChatClient structured-output planning:
 * {@code ChatModelCallAdvisor} only takes the provider-native structured-output path when the
 * request's {@code ChatOptions} is a {@link StructuredOutputChatOptions} instance - the generic
 * {@code ChatOptions} the previous {@code qaChatClient} bean used could never satisfy that check,
 * silently falling back to text-appended format instructions.
 */
class QaChatClientConfigurationTest {

    private final QaChatClientConfiguration configuration = new QaChatClientConfiguration();

    @Test
    void qaChatModel_usesInjectedObservationRegistry_notNoop() {
        QaModelProperties properties = new QaModelProperties("gemini-3.6-flash", "test-api-key");
        ObservationRegistry observationRegistry = ObservationRegistry.create();

        ChatModel chatModel = configuration.qaChatModel(properties, observationRegistry);

        Object actualRegistry = ReflectionTestUtils.getField(chatModel, "observationRegistry");
        assertNotSame(ObservationRegistry.NOOP, actualRegistry);
    }

    @Test
    void qaChatModel_defaultOptions_areStructuredOutputCapable() {
        QaModelProperties modelProperties = new QaModelProperties("gemini-3.6-flash", "test-api-key");
        ChatModel qaChatModel = configuration.qaChatModel(modelProperties, ObservationRegistry.create());

        Object defaultOptions = ReflectionTestUtils.getField(qaChatModel, "defaultOptions");
        assertInstanceOf(StructuredOutputChatOptions.class, defaultOptions);
    }

    @Test
    void qaChatClient_defaultOptions_areGoogleGenAiChatOptions_notGenericChatOptions() {
        QaModelProperties modelProperties = new QaModelProperties("gemini-3.6-flash", "test-api-key");
        ChatModel qaChatModel = configuration.qaChatModel(modelProperties, ObservationRegistry.create());

        PromptTemplate systemTemplate = new PromptTemplate("system prompt");
        RetrievalAugmentationAdvisor advisor = mock(RetrievalAugmentationAdvisor.class);
        QaProperties qaProperties = new QaProperties();

        ChatClient chatClient = configuration.qaChatClient(qaChatModel, systemTemplate, advisor, qaProperties);

        Object defaultRequestSpec = ReflectionTestUtils.getField(chatClient, "defaultChatClientRequest");
        ChatOptions options = (ChatOptions) ReflectionTestUtils.getField(defaultRequestSpec, "chatOptions");
        assertInstanceOf(GoogleGenAiChatOptions.class, options);
        assertInstanceOf(StructuredOutputChatOptions.class, options);
    }
}
