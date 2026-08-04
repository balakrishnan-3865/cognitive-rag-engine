package com.skyshift.cognitiveragengine.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Drops generic outbound HTTP client spans (e.g. the raw "http post" call to Groq or Ollama)
 * before export. They duplicate the gen_ai.* chat/embedding spans that already wrap them with
 * richer, AI-specific attributes, adding tree depth in Langfuse without any unique signal.
 * Picked up automatically by Boot's OpenTelemetryTracingAutoConfiguration, which applies every
 * SpanExportingPredicate bean before spans reach the OTLP exporter.
 */
@Component
public class NoiseSuppressingSpanExportingPredicate implements SpanExportingPredicate {

    private static final Pattern GENERIC_HTTP_CLIENT_SPAN = Pattern.compile("(?i)http (get|post|put|patch|delete)");

    @Override
    public boolean isExportable(FinishedSpan span) {
        return !(span.getKind() == Span.Kind.CLIENT && GENERIC_HTTP_CLIENT_SPAN.matcher(span.getName()).matches());
    }
}