package com.skyshift.cognitiveragengine.classifier.model.enums;

/**
 * High-level routing classifications for incoming queries.
 * Determines the target architectural execution mode.
 */
public enum RoutingIntent {
    /**
     * Greetings and salutations.
     * Account setups, hello, goodbye -> Skips RAG & complex tools
     * Example: "Hello", "Hi there", "How are you?", "Set up my account"
     */
    GENERAL_GREETING,

    /**
     * Deep policy document and manual lookups requiring RAG.
     * Example: "What is a deductible?", "What's covered under my plan?", "Explain copay rules"
     */
    POLICY_DOCUMENT_RAG,

    /**
     * Transactional database lookups using API tools.
     * A single, deterministic claim-status lookup with no other data source involved.
     * Example: "What's the status of claim CLM-123?", "Check my claim history"
     */
    CLAIM_STATUS_TOOL,

    /**
     * Multi-part queries that require decomposition, or queries that need both a claims-status
     * lookup and a policy-document lookup to answer fully -> Routed through query planning and
     * the subquery execution loop.
     * Example: "What's the status of my last claim, and does my plan cover physical therapy?"
     */
    COMPLEX_MULTI_SOURCE,

    /**
     * Malicious, diagnostic, or out-of-scope topics.
     * Immediate refusal without RAG or tools
     * Example: "Tell me a joke", "What's the weather?", diagnostic questions
     */
    OUT_OF_SCOPE
}