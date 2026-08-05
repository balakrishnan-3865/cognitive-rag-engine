package com.skyshift.cognitiveragengine.classifier.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;


@Slf4j
@Configuration
public class IntentClassifierConfiguration {

    @Bean("intentClassificationChatModel")
    public ChatModel intentClassificationChatModel(IntentClassifierModelProperties properties) {
        log.info("Initializing intent classifier chat model: {}", properties.name());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.name())
                .temperature(properties.options().temperature())
                .maxTokens(properties.options().maxTokens())
                .build();

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    @Bean("intentClassificationChatClient")
    public ChatClient intentClassificationChatClient(ChatModel intentClassificationChatModel) {
        return ChatClient.builder(intentClassificationChatModel).build();
    }

    @Bean("intentClassificationPromptTemplate")
    public PromptTemplate intentClassificationPromptTemplate() {
        return new PromptTemplate(new ClassPathResource("prompts/classifier/intent-classifier.txt"));
    }
}