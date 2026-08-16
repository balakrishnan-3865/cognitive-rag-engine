package com.skyshift.cognitiveragengine.ingestion.client;

import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingConvertRequest;
import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingStatusResponse;
import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingSubmitResponse;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingTaskStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;

/**
 * Thin wrapper around the Docling sidecar's async convert contract, verified live in Phase 0
 * (docs/overview/docling-parsing/DOCLING_PHASE_COMPLETION.md). Nothing Docling-specific is meant
 * to leak past this class (Section 13's anti-corruption boundary).
 */
@Component
public class DoclingClient {

    private static final String SUBMIT_PATH = "/v1/convert/source/async";
    private static final String POLL_PATH = "/v1/status/poll/{taskId}";
    private static final String RESULT_PATH = "/v1/result/{taskId}";

    private final RestClient restClient;

    public DoclingClient(RestClient doclingRestClient) {
        this.restClient = doclingRestClient;
    }

    /**
     * Submits file bytes directly ({@code kind: "file"}) rather than a URL — see
     * {@link DoclingConvertRequest} for why (Docling's SSRF guard blocks internal-network URLs).
     */
    public String submitAsync(byte[] fileBytes, String filename) {
        DoclingSubmitResponse response = restClient.post()
            .uri(SUBMIT_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(DoclingConvertRequest.forJsonOutput(fileBytes, filename))
            .retrieve()
            .body(DoclingSubmitResponse.class);

        return response.taskId();
    }

    public DoclingTaskStatus pollStatus(String taskId) {
        DoclingStatusResponse response = restClient.get()
            .uri(POLL_PATH, taskId)
            .retrieve()
            .body(DoclingStatusResponse.class);

        return DoclingTaskStatus.fromWireValue(response.taskStatus());
    }

    /**
     * Returns the raw result body stream, unread and unbuffered — the streaming parser
     * (Section 14) resolves the document tree directly off this, one item at a time, rather
     * than materializing the whole (potentially large) JSON payload in memory first.
     */
    public InputStream fetchResult(String taskId) {
        return restClient.get()
            .uri(RESULT_PATH, taskId)
            .exchange((request, response) -> response.getBody(), false);
    }
}
