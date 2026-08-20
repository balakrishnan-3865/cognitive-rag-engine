package com.skyshift.cognitiveragengine.ingestion.client;

import com.skyshift.cognitiveragengine.ingestion.model.enums.LlamaJobStatus;
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Phase 2: thin, tested client wrapping the Verification-confirmed LlamaParse contract (Q4/Q7).
 * Every assertion here is against a mocked HTTP server (MockRestServiceServer) — never a live
 * hosted LlamaParse instance (no local sidecar to point at, per Verification Q7).
 */
class LlamaParseClientTest {

    private static final String BASE_URL = "http://llama-test";

    private MockRestServiceServer mockServer;
    private LlamaParseClient llamaParseClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        llamaParseClient = new LlamaParseClient(builder.build());
    }

    @Test
    void uploadFile_sendsMultipartWithPurposeAndFile_andReturnsFileId() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/beta/files"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andExpect(content().string(containsString("name=\"purpose\"")))
            .andExpect(content().string(containsString("parse")))
            .andExpect(content().string(containsString("policy.pdf")))
            .andRespond(withSuccess("{\"id\":\"file-abc-123\"}", MediaType.APPLICATION_JSON));

        String fileId = llamaParseClient.uploadFile(
            "%PDF-1.4 fake content".getBytes(StandardCharsets.UTF_8), "policy.pdf");

        assertEquals("file-abc-123", fileId);
        mockServer.verify();
    }

    @Test
    void submitParseJob_sendsFileIdTierVersion_andReturnsJobId() {
        mockServer.expect(requestTo(BASE_URL + "/api/v2/parse"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.file_id").value("file-abc-123"))
            .andExpect(jsonPath("$.tier").value("cost_effective"))
            .andExpect(jsonPath("$.version").value("latest"))
            .andRespond(withSuccess(
                "{\"id\":\"job-xyz-789\",\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));

        String jobId = llamaParseClient.submitParseJob("file-abc-123", "cost_effective", "latest");

        assertEquals("job-xyz-789", jobId);
        mockServer.verify();
    }

    @ParameterizedTest
    @CsvSource({
        "PENDING, PENDING",
        "COMPLETED, COMPLETED",
        "FAILED, FAILED",
        "CANCELLED, CANCELLED"
    })
    void pollStatus_readsJobDotStatus_notTopLevelStatus(String wireValue, LlamaJobStatus expected) {
        mockServer.expect(requestTo(BASE_URL + "/api/v2/parse/job-xyz-789"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                "{\"job\":{\"id\":\"job-xyz-789\",\"status\":\"" + wireValue + "\"}}",
                MediaType.APPLICATION_JSON));

        LlamaJobStatus status = llamaParseClient.pollStatus("job-xyz-789");

        assertEquals(expected, status);
    }

    @Test
    void fetchResult_requestsExpandItems_andReturnsStreamIntact() throws Exception {
        String body = "{\"job\":{\"status\":\"COMPLETED\"},\"items\":{\"pages\":[]}}";
        mockServer.expect(requestTo(BASE_URL + "/api/v2/parse/job-xyz-789?expand=items"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        InputStream result = llamaParseClient.fetchResult("job-xyz-789");

        String actual = new String(result.readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(body, actual);
    }
}
