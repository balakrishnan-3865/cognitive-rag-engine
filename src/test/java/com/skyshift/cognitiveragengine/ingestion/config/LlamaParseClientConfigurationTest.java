package com.skyshift.cognitiveragengine.ingestion.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the {@code Authorization: Bearer <apiKey>} default header without ever hitting the
 * network: the request interceptor short-circuits before the real {@code ClientHttpRequestFactory}
 * is invoked, regardless of what that factory is (JDK-based here, per the timeout requirement).
 */
class LlamaParseClientConfigurationTest {

    private final LlamaParseClientConfiguration configuration = new LlamaParseClientConfiguration();

    @Test
    void llamaParseRestClient_setsAuthorizationHeaderFromProperties() {
        LlamaParseProperties props = new LlamaParseProperties(
            "http://llama-test", "test-api-key", "cost_effective", "latest", 4000, 150);

        AtomicReference<HttpHeaders> capturedHeaders = new AtomicReference<>();
        RestClient.Builder builder = RestClient.builder()
            .requestInterceptor((request, body, execution) -> {
                capturedHeaders.set(request.getHeaders());
                return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
            });

        RestClient restClient = configuration.llamaParseRestClient(builder, props);
        restClient.get().uri("/ping").retrieve().toBodilessEntity();

        assertEquals("Bearer test-api-key", capturedHeaders.get().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void llamaParseApiKeyValidator_validKey_doesNotThrow() {
        LlamaParseProperties props = new LlamaParseProperties(
            "http://llama-test", "test-api-key", "cost_effective", "latest", 4000, 150);

        configuration.llamaParseApiKeyValidator(props);
    }
}
