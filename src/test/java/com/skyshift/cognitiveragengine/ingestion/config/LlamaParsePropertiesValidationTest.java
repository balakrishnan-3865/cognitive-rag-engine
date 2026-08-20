package com.skyshift.cognitiveragengine.ingestion.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the fail-fast startup check (LlamaParseClientConfiguration): missing/blank
 * {@code llamaparse.api-key} only breaks context startup when {@code parser.strategy=llama} is
 * active; it must never fire for the {@code docling} default, where the key is legitimately unset.
 */
class LlamaParsePropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(RestClient.Builder.class, RestClient::builder)
        .withUserConfiguration(LlamaParseClientConfiguration.class);

    @Test
    void llamaStrategy_missingApiKey_contextFailsToStart() {
        contextRunner.withPropertyValues("parser.strategy=llama")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void llamaStrategy_blankApiKey_contextFailsToStart() {
        contextRunner.withPropertyValues("parser.strategy=llama", "llamaparse.api-key=")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void llamaStrategy_presentApiKey_contextStartsFine() {
        contextRunner.withPropertyValues("parser.strategy=llama", "llamaparse.api-key=test-key")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void doclingStrategy_missingApiKey_contextStartsFine() {
        contextRunner.withPropertyValues("parser.strategy=docling")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void strategyUnset_missingApiKey_contextStartsFine() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }
}
