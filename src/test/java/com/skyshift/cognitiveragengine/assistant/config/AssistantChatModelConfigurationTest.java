package com.skyshift.cognitiveragengine.assistant.config;

import com.skyshift.cognitiveragengine.common.ai.ChatModelTier;
import com.skyshift.cognitiveragengine.common.ai.FallbackChatModel;
import com.skyshift.cognitiveragengine.common.ai.GeminiModelProperties;
import com.skyshift.cognitiveragengine.common.ai.GroqModelProperties;
import com.skyshift.cognitiveragengine.common.ai.OpenRouterProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression guard for the multi-provider fallback wiring: the exposed
 * {@code assistantChatModel} bean must be a {@link FallbackChatModel} wrapping exactly 3 tiers
 * (Groq, OpenRouter/Nemotron, Gemini) in that order, each built with the real, injected
 * {@link ObservationRegistry}. Unlike QA/classifier, no tier needs a non-identity prompt
 * adapter - the ReAct agent communicates via tool calls and free text, never
 * {@code .entity()}/{@code .responseEntity()}.
 */
class AssistantChatModelConfigurationTest {

    private final AssistantChatModelConfiguration configuration = new AssistantChatModelConfiguration();

    private static GroqModelProperties groqModelProperties() {
        return new GroqModelProperties("openai/gpt-oss-20b", "test-groq-key", "https://api.groq.com/openai");
    }

    private static OpenRouterProperties openRouterProperties() {
        return new OpenRouterProperties(
                "nvidia/nemotron-3.5-lightning:free", "test-openrouter-key", "https://openrouter.ai/api");
    }

    private static GeminiModelProperties geminiModelProperties() {
        return new GeminiModelProperties("gemini-3.6-flash", "test-gemini-key");
    }

    @SuppressWarnings("unchecked")
    private static List<ChatModelTier> tiersOf(ChatModel chatModel) {
        assertInstanceOf(FallbackChatModel.class, chatModel);
        return (List<ChatModelTier>) ReflectionTestUtils.getField(chatModel, "tiers");
    }

    private ChatModel buildAssistantChatModel(ObservationRegistry observationRegistry) {
        return configuration.assistantChatModel(
                groqModelProperties(), openRouterProperties(), geminiModelProperties(),
                new AssistantProperties(), observationRegistry);
    }

    @Test
    void assistantChatModel_buildsThreeTiers_inGroqOpenRouterGeminiOrder() {
        ChatModel chatModel = buildAssistantChatModel(ObservationRegistry.create());

        List<ChatModelTier> tiers = tiersOf(chatModel);

        assertEquals(3, tiers.size());
        assertInstanceOf(OpenAiChatModel.class, tiers.get(0).model());
        assertInstanceOf(OpenAiChatModel.class, tiers.get(1).model());
        assertInstanceOf(GoogleGenAiChatModel.class, tiers.get(2).model());
    }

    @Test
    void assistantChatModel_eachTier_usesInjectedObservationRegistry_notNoop() {
        ObservationRegistry observationRegistry = ObservationRegistry.create();

        ChatModel chatModel = buildAssistantChatModel(observationRegistry);

        for (ChatModelTier tier : tiersOf(chatModel)) {
            Object actualRegistry = ReflectionTestUtils.getField(tier.model(), "observationRegistry");
            assertNotNull(actualRegistry);
            assertNotSame(ObservationRegistry.NOOP, actualRegistry);
            assertSame(observationRegistry, actualRegistry);
        }
    }

    @Test
    void assistantChatModel_noTierHasAPromptAdapter_allIdentity() {
        ChatModel chatModel = buildAssistantChatModel(ObservationRegistry.create());

        List<ChatModelTier> tiers = tiersOf(chatModel);

        assertEquals("groq", tiers.get(0).name());
        assertEquals("openrouter-nemotron", tiers.get(1).name());
        assertEquals("gemini", tiers.get(2).name());

        // No structured output is needed for the ReAct agent (tool calls + free text only), so
        // every tier keeps the untouched prompt - unlike QA/classifier's Nemotron tier.
        Prompt prompt = new Prompt("hi");
        for (ChatModelTier tier : tiers) {
            assertSame(prompt, tier.promptAdapter().apply(prompt));
        }
    }
}
