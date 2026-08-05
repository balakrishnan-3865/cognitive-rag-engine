package com.skyshift.cognitiveragengine.classifier.service;

import com.skyshift.cognitiveragengine.classifier.model.dto.IntentClassificationResponse;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Classifies incoming user queries to determine routing intent.
 * Uses two-pass classification:
 * Pass 1: Rule-based classifier (fast, cost-free) for common patterns
 * Pass 2: LLM classifier (cost-effective) for complex queries
 */
@Slf4j
@Component
public class IntentClassifier {

    private final RuleBasedClassifier ruleBasedClassifier;
    private final ChatClient intentClassificationChatClient;
    private final PromptTemplate intentClassificationPromptTemplate;

    public IntentClassifier(
            RuleBasedClassifier ruleBasedClassifier,
            @Qualifier("intentClassificationChatClient") ChatClient intentClassificationChatClient,
            @Qualifier("intentClassificationPromptTemplate") PromptTemplate intentClassificationPromptTemplate
    ) {
        this.ruleBasedClassifier = ruleBasedClassifier;
        this.intentClassificationChatClient = intentClassificationChatClient;
        this.intentClassificationPromptTemplate = intentClassificationPromptTemplate;
    }

    /**
     * Classify a user query using two-pass approach:
     * Pass 1: Rule-based classifier (fast, cost-free)
     * Pass 2: LLM classifier (if rules don't match)
     *
     * @param query The user's input query
     * @return IntentClassificationResponse with intent, confidence, and reasoning
     */
    public IntentClassificationResponse classify(String query) {
        // Pass 1: Try rule-based classification (fast, cost-free)
        IntentClassificationResponse ruleBasedResult = ruleBasedClassifier.matchRules(query);

        if (ruleBasedResult != null) {
            log.info("Query matched rule-based classifier: intent={}, confidence={}, reasoning='{}'",
                    ruleBasedResult.intent(),
                    String.format("%.2f", ruleBasedResult.confidence()),
                    ruleBasedResult.reasoning());
            return ruleBasedResult;
        }

        // Pass 2: Fall back to LLM classification
        log.debug("Rule-based classification returned null, invoking LLM classifier");
        return callLlmClassifier(query);
    }

    /**
     * Classify a user query using LLM (cost-effective model).
     * Called as fallback when rule-based classification returns null.
     *
     * @param query The user's input query
     * @return IntentClassificationResponse with intent, confidence, and reasoning
     */
    private IntentClassificationResponse callLlmClassifier(String query) {
        long startTime = System.currentTimeMillis();

        try {
            // Render the prompt with the query
            String prompt = intentClassificationPromptTemplate.render(
                    Map.of("query", query)
            );

            // Call cost-optimized LLM model for classification with structured response
            IntentClassificationResponse response = intentClassificationChatClient.prompt(prompt)
                    .call()
                    .entity(IntentClassificationResponse.class);

            long elapsedMs = System.currentTimeMillis() - startTime;

            log.info("Query classified: intent={}, confidence={}, reasoning='{}', timeMs={}",
                    response.intent(),
                    String.format("%.2f", response.confidence()),
                    response.reasoning(),
                    elapsedMs);

            return response;

        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.warn("Intent classification failed: {}, defaulting to POLICY_DOCUMENT_RAG, timeMs={}",
                    e.getMessage(), elapsedMs);

            // Safe fallback: treat unknown queries as policy document RAG
            return new IntentClassificationResponse(
                    RoutingIntent.POLICY_DOCUMENT_RAG,
                    0.0,
                    "Classification failed, defaulting to policy document RAG"
            );
        }
    }
}