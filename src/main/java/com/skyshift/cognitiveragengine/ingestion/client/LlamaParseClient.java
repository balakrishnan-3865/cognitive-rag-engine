package com.skyshift.cognitiveragengine.ingestion.client;

import com.skyshift.cognitiveragengine.ingestion.model.dto.LlamaJobStatusResponse;
import com.skyshift.cognitiveragengine.ingestion.model.dto.LlamaSubmitRequest;
import com.skyshift.cognitiveragengine.ingestion.model.dto.LlamaSubmitResponse;
import com.skyshift.cognitiveragengine.ingestion.model.dto.LlamaUploadResponse;
import com.skyshift.cognitiveragengine.ingestion.model.enums.LlamaJobStatus;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.InputStream;

/**
 * Thin wrapper around LlamaParse's hosted upload-then-submit contract, verified live in
 * Verification (02-verification.md Q4/Q7). Nothing LlamaParse-specific is meant to leak past this
 * class (mirrors {@code DoclingClient}'s anti-corruption boundary).
 */
@Component
public class LlamaParseClient {

    private static final String UPLOAD_PATH = "/api/v1/beta/files";
    private static final String SUBMIT_PATH = "/api/v2/parse";
    private static final String POLL_PATH = "/api/v2/parse/{jobId}";
    private static final String RESULT_PATH = "/api/v2/parse/{jobId}?expand=items";

    private final RestClient restClient;

    public LlamaParseClient(RestClient llamaParseRestClient) {
        this.restClient = llamaParseRestClient;
    }

    /**
     * First of LlamaParse's two-call submit shape: uploads the raw file bytes and returns the
     * resulting file id, which {@link #submitParseJob} then references.
     */
    public String uploadFile(byte[] fileBytes, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("purpose", "parse");
        body.add("file", new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });

        LlamaUploadResponse response = restClient.post()
            .uri(UPLOAD_PATH)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body)
            .retrieve()
            .body(LlamaUploadResponse.class);

        return response.id();
    }

    /**
     * {@code tier} and {@code version} are mandatory on the wire (Verification Q4 contract
     * correction) — submitting without them returns a 422.
     */
    public String submitParseJob(String fileId, String tier, String version) {
        LlamaSubmitResponse response = restClient.post()
            .uri(SUBMIT_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new LlamaSubmitRequest(fileId, tier, version))
            .retrieve()
            .body(LlamaSubmitResponse.class);

        return response.id();
    }

    public LlamaJobStatus pollStatus(String jobId) {
        LlamaJobStatusResponse response = restClient.get()
            .uri(POLL_PATH, jobId)
            .retrieve()
            .body(LlamaJobStatusResponse.class);

        return LlamaJobStatus.fromWireValue(response.job().status());
    }

    /**
     * Returns the raw result body stream, unread and unbuffered — Phase 3's structural parser
     * resolves the {@code items.pages[]} tree directly off this, one item at a time, mirroring
     * {@code DoclingClient#fetchResult}.
     */
    public InputStream fetchResult(String jobId) {
        return restClient.get()
            .uri(RESULT_PATH, jobId)
            .exchange((request, response) -> response.getBody(), false);
    }
}
