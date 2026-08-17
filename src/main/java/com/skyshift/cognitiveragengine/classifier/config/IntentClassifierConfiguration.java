package com.skyshift.cognitiveragengine.classifier.config;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;


@Slf4j
@Configuration
@EnableConfigurationProperties(IntentClassifierModelProperties.class)
public class IntentClassifierConfiguration {

    @Bean(name = "intentClassificationChatModel", defaultCandidate = false)
    public ChatModel intentClassificationChatModel(
            IntentClassifierModelProperties properties, ObservationRegistry observationRegistry) {
        log.info("Initializing intent classifier chat model: {}", properties.name());

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

    @Bean(name = "intentClassificationChatClient", defaultCandidate = false)
    public ChatClient intentClassificationChatClient(ChatModel intentClassificationChatModel) {
        return ChatClient.builder(intentClassificationChatModel).build();
    }

    @Bean("intentClassificationPromptTemplate")
    public PromptTemplate intentClassificationPromptTemplate() {
        return new PromptTemplate(new ClassPathResource("prompts/classifier/intent-classifier.st"));
    }
}