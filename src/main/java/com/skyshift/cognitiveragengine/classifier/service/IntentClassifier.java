package com.skyshift.cognitiveragengine.classifier.service;

import com.skyshift.cognitiveragengine.classifier.model.dto.IntentClassificationResponse;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
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

            // Call cost-optimized LLM model for classification with structured response.
            // ENABLE_NATIVE_STRUCTURED_OUTPUT makes the provider itself constrain decoding to the
            // JSON schema (response_format: json_schema) rather than relying on an appended text
            // instruction the model might ignore.
            var responseEntity = intentClassificationChatClient.prompt(prompt)
                    .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                    .call()
                    .responseEntity(IntentClassificationResponse.class);

            IntentClassificationResponse response = responseEntity.entity();
            ChatResponse chatResponse = responseEntity.response();
            long elapsedMs = System.currentTimeMillis() - startTime;

            log.info("Query classified: intent={}, confidence={}, reasoning='{}', timeMs={}",
                    response.intent(),
                    String.format("%.2f", response.confidence()),
                    response.reasoning(),
                    elapsedMs);

            if (chatResponse != null && chatResponse.getMetadata() != null
                    && chatResponse.getMetadata().getUsage() != null) {
                var usage = chatResponse.getMetadata().getUsage();
                log.info("Classifier token usage: prompt={}, completion={}, total={}",
                        usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
            }

            return response;

        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            log.warn("Intent classification failed: {}, defaulting to AGENT_QUERY, timeMs={}",
                    e.getMessage(), elapsedMs);

            // Safe fallback: route unknown queries through the unified ReAct agent rather than
            // refusing them outright
            return new IntentClassificationResponse(
                    RoutingIntent.AGENT_QUERY,
                    0.0,
                    "Classification failed, defaulting to agent query"
            );
        }
    }
}