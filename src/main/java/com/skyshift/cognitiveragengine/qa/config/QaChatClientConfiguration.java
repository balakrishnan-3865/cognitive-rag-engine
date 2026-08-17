package com.skyshift.cognitiveragengine.qa.config;

import com.google.genai.Client;
import com.skyshift.cognitiveragengine.qa.service.ReadyChunkDocumentRetriever;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Slf4j
@EnableConfigurationProperties({QaProperties.class, QaModelProperties.class})
@Configuration
public class QaChatClientConfiguration {

    @Bean("qaSystemPromptTemplate")
    public PromptTemplate qaSystemPromptTemplate() {
        return PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/qa/rag-system.st"))
                .build();
    }

    @Bean("qaContextPromptTemplate")
    public PromptTemplate qaContextPromptTemplate() {
        return PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/qa/rag-context.st"))
                .build();
    }

    @Bean("qaQueryPromptTemplate")
    public PromptTemplate qaQueryPromptTemplate() {
        return PromptTemplate.builder()
                .resource(new ClassPathResource("prompts/qa/rag-query.st"))
                .build();
    }

    @Bean
    public RetrievalAugmentationAdvisor qaRetrievalAdvisor(
            ReadyChunkDocumentRetriever readyChunkDocumentRetriever,
            @Qualifier("qaContextPromptTemplate") PromptTemplate qaContextPromptTemplate
    ) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(readyChunkDocumentRetriever)
                .queryAugmenter(new ContextualQueryAugmenter.Builder()
                        .allowEmptyContext(true)
                        .promptTemplate(qaContextPromptTemplate)
                        .build())
                .build();
    }

    /**
     * Dedicated QA answer model - independent of the app-wide {@code spring.ai.model.chat}
     * default (same rationale as {@code IntentClassifierConfiguration}'s dedicated Groq bean):
     * native structured output only engages when the request's {@code ChatOptions} is the
     * provider-specific type ({@link GoogleGenAiChatOptions} implements
     * {@code StructuredOutputChatOptions}), which the generic {@code ChatOptions} the previous
     * default-builder-based bean used could never satisfy.
     */
    @Bean(name = "qaChatModel", defaultCandidate = false)
    public ChatModel qaChatModel(QaModelProperties qaModelProperties, ObservationRegistry observationRegistry) {
        log.info("Initializing QA chat model: {}", qaModelProperties.name());

        Client genAiClient = Client.builder().apiKey(qaModelProperties.apiKey()).build();

        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(qaModelProperties.name())
                // Explicit, not left to the model's default - guards against Gemini's thinking
                // output being merged into the answer text for a "thinking"-capable model.
                .includeThoughts(false)
                .build();

        return GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                .defaultOptions(options)
                .observationRegistry(observationRegistry)
                .build();
    }

    @Bean
    public ChatClient qaChatClient(
            @Qualifier("qaChatModel") ChatModel qaChatModel,
            @Qualifier("qaSystemPromptTemplate") PromptTemplate qaSystemPromptTemplate,
            RetrievalAugmentationAdvisor qaRetrievalAdvisor,
            QaProperties qaProperties
    ) {
        return ChatClient.builder(qaChatModel)
                .defaultSystem(qaSystemPromptTemplate.getTemplate())
                .defaultAdvisors(qaRetrievalAdvisor)
                .defaultOptions(GoogleGenAiChatOptions.builder()
                        .maxOutputTokens(qaProperties.getMaxTokens())
                        .temperature(qaProperties.getTemperature())
                        .build())
                .build();
    }
}
