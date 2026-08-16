package com.skyshift.cognitiveragengine.ingestion.client;

import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Phase 3: thin, tested client wrapping the Phase 0-verified Docling contract. Every assertion
 * here is against a mocked HTTP server (MockRestServiceServer) — never a live Docling instance.
 */
class DoclingClientTest {

    private static final String BASE_URL = "http://docling-test:5001";

    private MockRestServiceServer mockServer;
    private DoclingClient doclingClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        doclingClient = new DoclingClient(builder.build());
    }

    @Test
    void submitAsync_sendsExactContractShape_andReturnsTaskId() {
        byte[] fileBytes = "%PDF-1.4 fake content".getBytes(StandardCharsets.UTF_8);
        String expectedBase64 = java.util.Base64.getEncoder().encodeToString(fileBytes);

        mockServer.expect(requestTo(BASE_URL + "/v1/convert/source/async"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.sources[0].kind").value("file"))
            .andExpect(jsonPath("$.sources[0].base64_string").value(expectedBase64))
            .andExpect(jsonPath("$.sources[0].filename").value("policy.pdf"))
            .andExpect(jsonPath("$.options.to_formats[0]").value("json"))
            .andRespond(withSuccess("{\"task_id\":\"task-abc-123\"}", MediaType.APPLICATION_JSON));

        String taskId = doclingClient.submitAsync(fileBytes, "policy.pdf");

        assertEquals("task-abc-123", taskId);
        mockServer.verify();
    }

    @ParameterizedTest
    @CsvSource({
        "pending, PENDING",
        "started, STARTED",
        "success, SUCCESS",
        "failure, FAILURE"
    })
    void pollStatus_parsesLowercaseWireValues(String wireValue, DoclingTaskStatus expected) {
        mockServer.expect(requestTo(BASE_URL + "/v1/status/poll/task-abc-123"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"task_status\":\"" + wireValue + "\"}", MediaType.APPLICATION_JSON));

        DoclingTaskStatus status = doclingClient.pollStatus("task-abc-123");

        assertEquals(expected, status);
    }

    @Test
    void fetchResult_returnsInputStreamWithContentIntact_notPreConsumed() throws Exception {
        String body = "{\"document\":{\"json_content\":{}}}";
        mockServer.expect(requestTo(BASE_URL + "/v1/result/task-abc-123"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        InputStream result = doclingClient.fetchResult("task-abc-123");

        // The client must hand back the stream without having drained it (no .readAllBytes()/
        // .body(String.class) internally) — Section 14's streaming parser depends on reading it
        // itself. Proven here by reading it out ourselves and getting the full body back intact.
        String actual = new String(result.readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(body, actual);
    }
}
