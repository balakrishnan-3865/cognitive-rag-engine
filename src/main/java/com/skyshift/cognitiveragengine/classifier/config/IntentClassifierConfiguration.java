package com.skyshift.cognitiveragengine.classifier.config;

import com.google.genai.Client;
import com.skyshift.cognitiveragengine.classifier.model.dto.IntentClassificationResponse;
import com.skyshift.cognitiveragengine.common.ai.ChatModelTier;
import com.skyshift.cognitiveragengine.common.ai.FallbackChatModel;
import com.skyshift.cognitiveragengine.common.ai.GeminiModelProperties;
import com.skyshift.cognitiveragengine.common.ai.OpenRouterProperties;
import com.skyshift.cognitiveragengine.common.ai.StructuredOutputPromptAdapters;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;


@Slf4j
@Configuration
@EnableConfigurationProperties({IntentClassifierModelProperties.class, OpenRouterProperties.class, GeminiModelProperties.class})
public class IntentClassifierConfiguration {

    private ChatModel groqChatModel(IntentClassifierModelProperties properties, ObservationRegistry observationRegistry) {
        log.info("Initializing intent classifier chat model (tier 1, Groq): {}", properties.name());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.name())
                .temperature(properties.options().temperature())
                .maxTokens(properties.options().maxTokens())
                // gpt-oss-20b is a reasoning model; without this Groq leaves its reasoning/
                // analysis-channel output inline in `content`, which breaks JSON parsing of the
                // classifier's structured response. extraBody is @JsonAnyGetter-flattened to the
                // top-level request JSON, so this becomes a literal "reasoning_format" field.
                .extraBody(Map.of("reasoning_format", "hidden"))
                .build();

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                // Without this, OpenAiChatModel.Builder defaults to ObservationRegistry.NOOP -
                // the classifier's LLM calls would produce zero OTel spans/metrics, unlike the
                // autoconfigured chat model beans other services use.
                .observationRegistry(observationRegistry)
                .build();
    }

    private ChatModel nemotronChatModel(
            OpenRouterProperties openRouterProperties,
            IntentClassifierModelProperties classifierProperties,
            ObservationRegistry observationRegistry) {
        log.info("Initializing intent classifier chat model (tier 2, OpenRouter/Nemotron): {}", openRouterProperties.name());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(openRouterProperties.name())
                .temperature(classifierProperties.options().temperature())
                .maxTokens(classifierProperties.options().maxTokens())
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

    private ChatModel geminiChatModel(
            GeminiModelProperties geminiModelProperties,
            IntentClassifierModelProperties classifierProperties,
            ObservationRegistry observationRegistry) {
        log.info("Initializing intent classifier chat model (tier 3, Gemini): {}", geminiModelProperties.name());

        Client genAiClient = Client.builder().apiKey(geminiModelProperties.apiKey()).build();

        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(geminiModelProperties.name())
                .temperature(classifierProperties.options().temperature())
                .maxOutputTokens(classifierProperties.options().maxTokens())
                .build();

        return GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    @Bean(name = "intentClassificationChatModel", defaultCandidate = false)
    public ChatModel intentClassificationChatModel(
            IntentClassifierModelProperties properties,
            OpenRouterProperties openRouterProperties,
            GeminiModelProperties geminiModelProperties,
            ObservationRegistry observationRegistry) {

        ChatModel groq = groqChatModel(properties, observationRegistry);
        ChatModel nemotron = nemotronChatModel(openRouterProperties, properties, observationRegistry);
        ChatModel gemini = geminiChatModel(geminiModelProperties, properties, observationRegistry);

        return new FallbackChatModel(List.of(
                ChatModelTier.of("groq", groq),
                new ChatModelTier("openrouter-nemotron", nemotron,
                        StructuredOutputPromptAdapters.appendFormatInstructions(IntentClassificationResponse.class)),
                ChatModelTier.of("gemini", gemini)));
    }

    @Bean(name = "intentClassificationChatClient", defaultCandidate = false)
    public ChatClient intentClassificationChatClient(ChatModel intentClassificationChatModel) {
        return ChatClient.builder(intentClassificationChatModel).build();
    }

    @Bean("intentClassificationPromptTemplate")
    public PromptTemplate intentClassificationPromptTemplate() {
        return new PromptTemplate(new ClassPathResource("prompts/classifier/intent-classifier.st"));
    }
}