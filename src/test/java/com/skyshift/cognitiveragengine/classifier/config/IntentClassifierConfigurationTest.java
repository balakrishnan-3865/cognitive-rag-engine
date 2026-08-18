package com.skyshift.cognitiveragengine.classifier.config;

import com.skyshift.cognitiveragengine.common.ai.ChatModelTier;
import com.skyshift.cognitiveragengine.common.ai.FallbackChatModel;
import com.skyshift.cognitiveragengine.common.ai.GeminiModelProperties;
import com.skyshift.cognitiveragengine.common.ai.OpenRouterProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the observability gap found during ChatClient structured-output
 * planning: {@link OpenAiChatModel.Builder} defaults {@code observationRegistry} to
 * {@link ObservationRegistry#NOOP}, so a hand-built {@link ChatModel} bean that never calls
 * {@code .observationRegistry(...)} silently produces zero OTel spans/metrics.
 *
 * <p>Also guards the multi-provider fallback wiring: the exposed
 * {@code intentClassificationChatModel} bean must be a {@link FallbackChatModel} wrapping
 * exactly 3 tiers (Groq, OpenRouter/Nemotron, Gemini) in that order, each built with the
 * real, injected {@link ObservationRegistry}.
 */
class IntentClassifierConfigurationTest {

    private final IntentClassifierConfiguration configuration = new IntentClassifierConfiguration();

    private static IntentClassifierModelProperties classifierProperties() {
        return new IntentClassifierModelProperties(
                "openai/gpt-oss-20b",
                "test-api-key",
                "https://api.groq.com/openai/v1",
                new IntentClassifierModelProperties.Options(0.1, 200));
    }

    private static OpenRouterProperties openRouterProperties() {
        return new OpenRouterProperties(
                "nvidia/nemotron-3.5-lightning:free",
                "test-openrouter-key",
                "https://openrouter.ai/api/v1");
    }

    private static GeminiModelProperties geminiModelProperties() {
        return new GeminiModelProperties("gemini-3.6-flash", "test-gemini-key");
    }

    @SuppressWarnings("unchecked")
    private static List<ChatModelTier> tiersOf(ChatModel chatModel) {
        assertInstanceOf(FallbackChatModel.class, chatModel);
        return (List<ChatModelTier>) ReflectionTestUtils.getField(chatModel, "tiers");
    }

    @Test
    void intentClassificationChatModel_buildsThreeTiers_inGroqOpenRouterGeminiOrder() {
        ChatModel chatModel = configuration.intentClassificationChatModel(
                classifierProperties(), openRouterProperties(), geminiModelProperties(), ObservationRegistry.create());

        List<ChatModelTier> tiers = tiersOf(chatModel);

        assertEquals(3, tiers.size());
        assertInstanceOf(OpenAiChatModel.class, tiers.get(0).model());
        assertInstanceOf(OpenAiChatModel.class, tiers.get(1).model());
        assertInstanceOf(GoogleGenAiChatModel.class, tiers.get(2).model());
    }

    @Test
    void intentClassificationChatModel_eachTier_usesInjectedObservationRegistry_notNoop() {
        ObservationRegistry observationRegistry = ObservationRegistry.create();

        ChatModel chatModel = configuration.intentClassificationChatModel(
                classifierProperties(), openRouterProperties(), geminiModelProperties(), observationRegistry);

        for (ChatModelTier tier : tiersOf(chatModel)) {
            Object actualRegistry = ReflectionTestUtils.getField(tier.model(), "observationRegistry");
            assertNotNull(actualRegistry);
            assertNotSame(ObservationRegistry.NOOP, actualRegistry);
            assertSame(observationRegistry, actualRegistry);
        }
    }

    @Test
    void intentClassificationChatModel_onlyNemotronTier_hasNonIdentityPromptAdapter() {
        ChatModel chatModel = configuration.intentClassificationChatModel(
                classifierProperties(), openRouterProperties(), geminiModelProperties(), ObservationRegistry.create());

        List<ChatModelTier> tiers = tiersOf(chatModel);

        assertEquals("groq", tiers.get(0).name());
        assertEquals("openrouter-nemotron", tiers.get(1).name());
        assertEquals("gemini", tiers.get(2).name());

        // Groq and Gemini tiers keep the untouched prompt (identity adapter); only the
        // Nemotron tier needs the text-instruction adapter, since OpenRouter's free
        // Nemotron model has no response_format/structured_outputs support at all.
        org.springframework.ai.chat.prompt.Prompt prompt = new org.springframework.ai.chat.prompt.Prompt("hi");
        assertSame(prompt, tiers.get(0).promptAdapter().apply(prompt));
        assertSame(prompt, tiers.get(2).promptAdapter().apply(prompt));
        assertTrue(tiers.get(1).promptAdapter().apply(prompt).getInstructions().get(0).getText().length()
                > prompt.getInstructions().get(0).getText().length());
    }
}
