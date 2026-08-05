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
     * Claim IDs, status tracking, account queries -> Triggers API Tools
     * Example: "What's the status of claim CLM-123?", "Check my claim history"
     */
    CLAIM_STATUS_TOOL,

    /**
     * Malicious, diagnostic, or out-of-scope topics.
     * Immediate refusal without RAG or tools
     * Example: "Tell me a joke", "What's the weather?", diagnostic questions
     */
    OUT_OF_SCOPE
}