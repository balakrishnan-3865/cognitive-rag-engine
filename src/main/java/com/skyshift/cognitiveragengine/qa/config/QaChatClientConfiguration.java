package com.skyshift.cognitiveragengine.qa.config;

import com.google.genai.Client;
import com.skyshift.cognitiveragengine.common.ai.ChatModelTier;
import com.skyshift.cognitiveragengine.common.ai.FallbackChatModel;
import com.skyshift.cognitiveragengine.common.ai.GeminiModelProperties;
import com.skyshift.cognitiveragengine.common.ai.GroqModelProperties;
import com.skyshift.cognitiveragengine.common.ai.OpenRouterProperties;
import com.skyshift.cognitiveragengine.common.ai.StructuredOutputPromptAdapters;
import com.skyshift.cognitiveragengine.qa.model.KnowledgeSourceResponse;
import com.skyshift.cognitiveragengine.qa.service.ReadyChunkDocumentRetriever;
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
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

@Slf4j
@EnableConfigurationProperties({QaProperties.class, GroqModelProperties.class, OpenRouterProperties.class, GeminiModelProperties.class})
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

    private ChatModel groqChatModel(GroqModelProperties groqModelProperties, QaProperties qaProperties, ObservationRegistry observationRegistry) {
        log.info("Initializing QA chat model (tier 1, Groq): {}", groqModelProperties.name());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(groqModelProperties.name())
                .temperature(qaProperties.getTemperature())
                .maxTokens(qaProperties.getMaxTokens())
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

    private ChatModel nemotronChatModel(OpenRouterProperties openRouterProperties, QaProperties qaProperties, ObservationRegistry observationRegistry) {
        log.info("Initializing QA chat model (tier 2, OpenRouter/Nemotron): {}", openRouterProperties.name());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(openRouterProperties.name())
                .temperature(qaProperties.getTemperature())
                .maxTokens(qaProperties.getMaxTokens())
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

    private ChatModel geminiChatModel(GeminiModelProperties geminiModelProperties, QaProperties qaProperties, ObservationRegistry observationRegistry) {
        log.info("Initializing QA chat model (tier 3, Gemini): {}", geminiModelProperties.name());

        Client genAiClient = Client.builder().apiKey(geminiModelProperties.apiKey()).build();

        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(geminiModelProperties.name())
                .temperature(qaProperties.getTemperature())
                .maxOutputTokens(qaProperties.getMaxTokens())
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

    /**
     * Dedicated QA answer model - independent of the app-wide {@code spring.ai.model.chat}
     * default (same rationale as {@code IntentClassifierConfiguration}'s dedicated Groq bean).
     * Tries Groq first, OpenRouter's Nemotron second, Gemini last - see
     * {@code .claude/plans/multi-provider-fallback/02-plan.md}. Only the Nemotron tier needs
     * {@link StructuredOutputPromptAdapters} - it has no {@code response_format}/
     * {@code structured_outputs} support at all (confirmed via OpenRouter's model API), unlike
     * Groq and Gemini which both implement {@code StructuredOutputChatOptions} natively.
     */
    @Bean(name = "qaChatModel", defaultCandidate = false)
    public ChatModel qaChatModel(
            GroqModelProperties groqModelProperties,
            OpenRouterProperties openRouterProperties,
            GeminiModelProperties geminiModelProperties,
            QaProperties qaProperties,
            ObservationRegistry observationRegistry) {

        ChatModel groq = groqChatModel(groqModelProperties, qaProperties, observationRegistry);
        ChatModel nemotron = nemotronChatModel(openRouterProperties, qaProperties, observationRegistry);
        ChatModel gemini = geminiChatModel(geminiModelProperties, qaProperties, observationRegistry);

        return new FallbackChatModel(List.of(
                ChatModelTier.of("groq", groq),
                new ChatModelTier("openrouter-nemotron", nemotron,
                        StructuredOutputPromptAdapters.appendFormatInstructions(KnowledgeSourceResponse.class)),
                ChatModelTier.of("gemini", gemini)));
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
                .defaultOptions(OpenAiChatOptions.builder()
                        .maxTokens(qaProperties.getMaxTokens())
                        .temperature(qaProperties.getTemperature())
                        .build())
                .build();
    }
}
