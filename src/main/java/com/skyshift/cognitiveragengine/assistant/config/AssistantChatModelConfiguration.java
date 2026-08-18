package com.skyshift.cognitiveragengine.assistant.config;

import com.google.genai.Client;
import com.skyshift.cognitiveragengine.common.ai.ChatModelTier;
import com.skyshift.cognitiveragengine.common.ai.FallbackChatModel;
import com.skyshift.cognitiveragengine.common.ai.GeminiModelProperties;
import com.skyshift.cognitiveragengine.common.ai.GroqModelProperties;
import com.skyshift.cognitiveragengine.common.ai.OpenRouterProperties;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Dedicated Assistant/Claims ReAct agent model - independent of the app-wide
 * {@code spring.ai.model.chat} default, following the same dedicated-bean pattern as
 * {@code QaChatClientConfiguration}/{@code IntentClassifierConfiguration}. Tries Groq
 * first, OpenRouter's Nemotron second, Gemini last - see
 * {@code .claude/plans/multi-provider-fallback/02-plan.md}. Unlike QA/classifier, no
 * structured output/promptAdapter is needed on any tier - the ReAct agent communicates
 * via tool calls and free text, never {@code .entity()}/{@code .responseEntity()}.
 */
@Slf4j
@EnableConfigurationProperties({AssistantProperties.class, GroqModelProperties.class, OpenRouterProperties.class, GeminiModelProperties.class})
@Configuration
public class AssistantChatModelConfiguration {

    private ChatModel groqChatModel(GroqModelProperties groqModelProperties, AssistantProperties assistantProperties, ObservationRegistry observationRegistry) {
        log.info("Initializing Assistant chat model (tier 1, Groq): {}", groqModelProperties.name());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(groqModelProperties.name())
                .temperature(assistantProperties.getTemperature())
                .maxTokens(assistantProperties.getMaxTokens())
                .build();

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(groqModelProperties.baseUrl())
                .apiKey(groqModelProperties.apiKey())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    private ChatModel nemotronChatModel(OpenRouterProperties openRouterProperties, AssistantProperties assistantProperties, ObservationRegistry observationRegistry) {
        log.info("Initializing Assistant chat model (tier 2, OpenRouter/Nemotron): {}", openRouterProperties.name());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(openRouterProperties.name())
                .temperature(assistantProperties.getTemperature())
                .maxTokens(assistantProperties.getMaxTokens())
                .build();

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(openRouterProperties.baseUrl())
                .apiKey(openRouterProperties.apiKey())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    private ChatModel geminiChatModel(GeminiModelProperties geminiModelProperties, AssistantProperties assistantProperties, ObservationRegistry observationRegistry) {
        log.info("Initializing Assistant chat model (tier 3, Gemini): {}", geminiModelProperties.name());

        Client genAiClient = Client.builder().apiKey(geminiModelProperties.apiKey()).build();

        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(geminiModelProperties.name())
                .temperature(assistantProperties.getTemperature())
                .maxOutputTokens(assistantProperties.getMaxTokens())
                .build();

        return GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    @Bean(name = "assistantChatModel", defaultCandidate = false)
    public ChatModel assistantChatModel(
            GroqModelProperties groqModelProperties,
            OpenRouterProperties openRouterProperties,
            GeminiModelProperties geminiModelProperties,
            AssistantProperties assistantProperties,
            ObservationRegistry observationRegistry) {

        ChatModel groq = groqChatModel(groqModelProperties, assistantProperties, observationRegistry);
        ChatModel nemotron = nemotronChatModel(openRouterProperties, assistantProperties, observationRegistry);
        ChatModel gemini = geminiChatModel(geminiModelProperties, assistantProperties, observationRegistry);

        return new FallbackChatModel(List.of(
                ChatModelTier.of("groq", groq),
                ChatModelTier.of("openrouter-nemotron", nemotron),
                ChatModelTier.of("gemini", gemini)));
    }
}
