package com.skyshift.cognitiveragengine.ingestion.parser;

import com.skyshift.cognitiveragengine.ingestion.client.DoclingClient;
import com.skyshift.cognitiveragengine.ingestion.docling.DoclingChunkAssembler;
import com.skyshift.cognitiveragengine.ingestion.docling.DoclingDocumentParser;
import com.skyshift.cognitiveragengine.ingestion.docling.DoclingParseAndChunkStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Proves {@code parser.strategy} gates which {@link ParseAndChunkStrategy} bean is active,
 * without booting the full application context (no DB/RestClient infra needed).
 */
class ParserStrategySelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(StrategyBeansConfiguration.class);

    @Test
    void strategyUnset_doclingStrategyBeanActive() {
        contextRunner.run(context ->
            org.assertj.core.api.Assertions.assertThat(context)
                .hasSingleBean(DoclingParseAndChunkStrategy.class));
    }

    @Test
    void strategyDocling_doclingStrategyBeanActive() {
        contextRunner.withPropertyValues("parser.strategy=docling").run(context ->
            org.assertj.core.api.Assertions.assertThat(context)
                .hasSingleBean(DoclingParseAndChunkStrategy.class));
    }

    @Test
    void strategyLlama_doclingStrategyBeanAbsent() {
        contextRunner.withPropertyValues("parser.strategy=llama").run(context ->
            org.assertj.core.api.Assertions.assertThat(context)
                .doesNotHaveBean(DoclingParseAndChunkStrategy.class));
    }

    @Configuration
    @ComponentScan(
        basePackageClasses = DoclingParseAndChunkStrategy.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE, classes = DoclingParseAndChunkStrategy.class),
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
        })
    static class StrategyBeansConfiguration {

        @Bean
        DoclingClient doclingClient() {
            return Mockito.mock(DoclingClient.class);
        }

        @Bean
        DoclingDocumentParser doclingDocumentParser() {
            return Mockito.mock(DoclingDocumentParser.class);
        }

        @Bean
        DoclingChunkAssembler doclingChunkAssembler() {
            return Mockito.mock(DoclingChunkAssembler.class);
        }
    }
}
