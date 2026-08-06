package com.skyshift.cognitiveragengine.workflows.claims.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the claims agent orchestrator's StateGraph.
 * Binds to workflow.* in application.yaml.
 */
@ConfigurationProperties(prefix = "workflow")
public record WorkflowProperties(
        Integer maxSubqueries,
        Integer graphRecursionLimit
) {
    public WorkflowProperties {
        if (maxSubqueries == null) maxSubqueries = 3;
        if (graphRecursionLimit == null) graphRecursionLimit = 8;
    }
}
