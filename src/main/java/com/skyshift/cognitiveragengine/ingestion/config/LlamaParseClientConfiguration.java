package com.skyshift.cognitiveragengine.ingestion.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(LlamaParseProperties.class)
public class LlamaParseClientConfiguration {

    /**
     * Unlike Docling's compose-network sidecar (same-network, low latency), LlamaParse is a
     * public hosted API reached over the internet — explicit connect/read timeouts, rather than
     * relying on defaults, per the Non-Functional Checklist (03-plan.md).
     */
    @Bean
    public RestClient llamaParseRestClient(RestClient.Builder builder, LlamaParseProperties props) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        return builder
            .baseUrl(props.baseUrl())
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
            .build();
    }

    /**
     * Fails application startup fast when {@code parser.strategy=llama} is active but
     * {@code llamaparse.api-key} is missing/blank — avoids silently accepting documents that are
     * guaranteed to fail parsing with a 401 on the first request. Scoped to the {@code llama}
     * strategy so it never fires when {@code docling} (the default) is active, where
     * {@code llamaparse.api-key} is legitimately unset.
     */
    @Bean
    @ConditionalOnProperty(name = "parser.strategy", havingValue = "llama")
    public ApiKeyValidator llamaParseApiKeyValidator(LlamaParseProperties props) {
        return new ApiKeyValidator(props);
    }

    static final class ApiKeyValidator {
        ApiKeyValidator(LlamaParseProperties props) {
            if (props.apiKey() == null || props.apiKey().isBlank()) {
                throw new IllegalStateException(
                    "llamaparse.api-key must be set when parser.strategy=llama");
            }
        }
    }
}
