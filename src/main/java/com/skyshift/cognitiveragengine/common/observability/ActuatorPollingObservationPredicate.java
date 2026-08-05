package com.skyshift.cognitiveragengine.common.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Drops the observation for GET polling against /actuator/** (Prometheus scraping
 * /actuator/prometheus, health checks against /actuator/health) before it becomes a span.
 * These fire every few seconds and would otherwise flood Langfuse with traces that carry
 * no diagnostic signal. Picked up automatically by Boot's ObservationAutoConfiguration,
 * which applies every ObservationPredicate bean to the global ObservationRegistry.
 */
@Component
public class ActuatorPollingObservationPredicate implements ObservationPredicate {

    @Override
    public boolean test(String name, Observation.Context context) {
        if (context instanceof ServerRequestObservationContext serverContext) {
            HttpServletRequest request = serverContext.getCarrier();
            return !("GET".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().startsWith("/actuator"));
        }
        return true;
    }
}