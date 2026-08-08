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
     * Any non-trivial query - policy-document lookups, claim-status lookups, or queries needing
     * both - resolved by unified_react_agent's single ReAct tool-calling loop, which has both
     * KnowledgeBaseTool and ClaimStatusTool available and can call either or both as needed.
     * Example: "What is a deductible?", "What's the status of claim CLM-123?",
     * "What's the status of my last claim, and does my plan cover physical therapy?"
     */
    AGENT_QUERY,

    /**
     * Malicious, diagnostic, or out-of-scope topics.
     * Immediate refusal without RAG or tools
     * Example: "Tell me a joke", "What's the weather?", diagnostic questions
     */
    OUT_OF_SCOPE
}
