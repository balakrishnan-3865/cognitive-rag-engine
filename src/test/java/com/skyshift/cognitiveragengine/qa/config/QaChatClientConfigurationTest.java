package com.skyshift.cognitiveragengine.qa.config;

import com.skyshift.cognitiveragengine.common.ai.ChatModelTier;
import com.skyshift.cognitiveragengine.common.ai.FallbackChatModel;
import com.skyshift.cognitiveragengine.common.ai.GeminiModelProperties;
import com.skyshift.cognitiveragengine.common.ai.GroqModelProperties;
import com.skyshift.cognitiveragengine.common.ai.OpenRouterProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression guard for the multi-provider fallback wiring: the exposed {@code qaChatModel}
 * bean must be a {@link FallbackChatModel} wrapping exactly 3 tiers (Groq, OpenRouter/Nemotron,
 * Gemini) in that order, each built with the real, injected {@link ObservationRegistry}. Also
 * guards the earlier observability gap ({@link OpenAiChatModel.Builder}/
 * {@link GoogleGenAiChatModel.Builder} default {@code observationRegistry} to
 * {@link ObservationRegistry#NOOP} unless explicitly set).
 */
class QaChatClientConfigurationTest {

    private final QaChatClientConfiguration configuration = new QaChatClientConfiguration();

    private static GroqModelProperties groqModelProperties() {
        return new GroqModelProperties("openai/gpt-oss-20b", "test-groq-key", "https://api.groq.com/openai/v1");
    }

    private static OpenRouterProperties openRouterProperties() {
        return new OpenRouterProperties(
                "nvidia/nemotron-3.5-lightning:free", "test-openrouter-key", "https://openrouter.ai/api/v1");
    }

    private static GeminiModelProperties geminiModelProperties() {
        return new GeminiModelProperties("gemini-3.6-flash", "test-gemini-key");
    }

    @SuppressWarnings("unchecked")
    private static List<ChatModelTier> tiersOf(ChatModel chatModel) {
        assertInstanceOf(FallbackChatModel.class, chatModel);
        return (List<ChatModelTier>) ReflectionTestUtils.getField(chatModel, "tiers");
    }

    private ChatModel buildQaChatModel(ObservationRegistry observationRegistry) {
        return configuration.qaChatModel(
                groqModelProperties(), openRouterProperties(), geminiModelProperties(),
                new QaProperties(), observationRegistry);
    }

    @Test
    void qaChatModel_buildsThreeTiers_inGroqOpenRouterGeminiOrder() {
        ChatModel chatModel = buildQaChatModel(ObservationRegistry.create());

        List<ChatModelTier> tiers = tiersOf(chatModel);

        assertEquals(3, tiers.size());
        assertInstanceOf(OpenAiChatModel.class, tiers.get(0).model());
        assertInstanceOf(OpenAiChatModel.class, tiers.get(1).model());
        assertInstanceOf(GoogleGenAiChatModel.class, tiers.get(2).model());
    }

    @Test
    void qaChatModel_eachTier_usesInjectedObservationRegistry_notNoop() {
        ObservationRegistry observationRegistry = ObservationRegistry.create();

        ChatModel chatModel = buildQaChatModel(observationRegistry);

        for (ChatModelTier tier : tiersOf(chatModel)) {
            Object actualRegistry = ReflectionTestUtils.getField(tier.model(), "observationRegistry");
            assertNotNull(actualRegistry);
            assertNotSame(ObservationRegistry.NOOP, actualRegistry);
            assertSame(observationRegistry, actualRegistry);
        }
    }

    @Test
    void qaChatModel_onlyNemotronTier_hasNonIdentityPromptAdapter() {
        ChatModel chatModel = buildQaChatModel(ObservationRegistry.create());

        List<ChatModelTier> tiers = tiersOf(chatModel);

        assertEquals("groq", tiers.get(0).name());
        assertEquals("openrouter-nemotron", tiers.get(1).name());
        assertEquals("gemini", tiers.get(2).name());

        // Groq and Gemini both implement StructuredOutputChatOptions natively and keep the
        // untouched prompt (identity adapter); only Nemotron needs the text-instruction
        // adapter, since OpenRouter's free Nemotron model has no response_format/
        // structured_outputs support at all.
        Prompt prompt = new Prompt("hi");
        assertSame(prompt, tiers.get(0).promptAdapter().apply(prompt));
        assertSame(prompt, tiers.get(2).promptAdapter().apply(prompt));
        assertTrue(tiers.get(1).promptAdapter().apply(prompt).getInstructions().get(0).getText().length()
                > prompt.getInstructions().get(0).getText().length());
    }

    @Test
    void qaChatClient_defaultOptions_areOpenAiChatOptions_matchingThePrimaryTier() {
        ChatModel qaChatModel = buildQaChatModel(ObservationRegistry.create());

        PromptTemplate systemTemplate = new PromptTemplate("system prompt");
        RetrievalAugmentationAdvisor advisor = mock(RetrievalAugmentationAdvisor.class);
        QaProperties qaProperties = new QaProperties();

        ChatClient chatClient = configuration.qaChatClient(qaChatModel, systemTemplate, advisor, qaProperties);

        Object defaultRequestSpec = ReflectionTestUtils.getField(chatClient, "defaultChatClientRequest");
        ChatOptions options = (ChatOptions) ReflectionTestUtils.getField(defaultRequestSpec, "chatOptions");
        assertInstanceOf(OpenAiChatOptions.class, options);
        assertInstanceOf(StructuredOutputChatOptions.class, options);
    }
}
