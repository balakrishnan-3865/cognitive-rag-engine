package com.skyshift.cognitiveragengine.qa.config;

import com.skyshift.cognitiveragengine.qa.service.ReadyChunkDocumentRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Slf4j
@EnableConfigurationProperties(QaProperties.class)
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

    @Bean
    public ChatClient qaChatClient(
            ChatClient.Builder chatClientBuilder,
            @Qualifier("qaSystemPromptTemplate") PromptTemplate qaSystemPromptTemplate,
            RetrievalAugmentationAdvisor qaRetrievalAdvisor,
            QaProperties qaProperties
    ) {
        return chatClientBuilder
                .defaultSystem(qaSystemPromptTemplate.getTemplate())
                .defaultAdvisors(qaRetrievalAdvisor)
                .defaultOptions(ChatOptions.builder()
                        .maxTokens(qaProperties.getMaxTokens())
                        .temperature(qaProperties.getTemperature())
                        .build())
                .build();
    }
}
