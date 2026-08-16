package com.skyshift.cognitiveragengine.ingestion.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Base64;
import java.util.List;

/**
 * Phase 9 correction: submits file bytes directly ({@code kind: "file"}) rather than a presigned
 * URL ({@code kind: "http"}). Docling's own SSRF guard (docling_core's {@code _is_safe_url})
 * hard-rejects any URL resolving to a private/internal IP — which a compose-network hostname
 * like {@code minio} always is — so the presigned-URL design (Phases 3/4) can never work against
 * this deployment shape. Sending the file inline sidesteps the URL fetch entirely.
 */
public record DoclingConvertRequest(
        List<Source> sources,
        Options options
) {

    public static DoclingConvertRequest forJsonOutput(byte[] fileBytes, String filename) {
        String base64 = Base64.getEncoder().encodeToString(fileBytes);
        return new DoclingConvertRequest(
            List.of(new Source("file", base64, filename)),
            new Options(List.of("json")));
    }

    public record Source(
            String kind,
            @JsonProperty("base64_string") String base64String,
            String filename
    ) {}

    public record Options(
            @JsonProperty("to_formats") List<String> toFormats
    ) {}
}
