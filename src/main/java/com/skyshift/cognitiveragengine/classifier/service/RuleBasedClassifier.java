package com.skyshift.cognitiveragengine.classifier.service;

import com.skyshift.cognitiveragengine.classifier.model.dto.IntentClassificationResponse;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Rule-based intent classifier using a boundary-stripped regex pattern.
 * Provides fast, cost-free intent identification for common patterns.
 *
 * Single pass: Check for leading greeting only. If found with minimal trailing text → GENERAL_GREETING.
 * Everything else - including queries mentioning a claim ID - falls through to the LLM classifier,
 * which is the sole decider of AGENT_QUERY vs. OUT_OF_SCOPE for non-greeting input.
 * A structured claim-ID fast-path is deliberately not added here: claim-status, policy, and
 * multi-source queries all resolve to the same AGENT_QUERY intent regardless, so a fast-path
 * would only save one classification call while adding a second place routing logic can drift.
 *
 * Returns null if no rules match to delegate to LLM classifier.
 */
@Slf4j
@Component
public class RuleBasedClassifier {

    // Leading greeting pattern - matches at start of normalized input
    private static final Pattern LEADING_GREETING = Pattern.compile(
            "^(hello|hi|hey|goodbye|bye|greetings|howdy)\\s*[,.]?\\s*",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Classify a query using rule-based regex patterns.
     *
     * Strip a leading greeting. If nothing substantial remains → GENERAL_GREETING.
     * Otherwise, delegate to the LLM classifier.
     *
     * @param userInput The user's input query
     * @return IntentClassificationResponse if a rule matches, null if no rules match (delegate to LLM)
     */
    public IntentClassificationResponse matchRules(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return new IntentClassificationResponse(
                    RoutingIntent.OUT_OF_SCOPE,
                    1.0,
                    "Empty input."
            );
        }

        String normalized = userInput.trim();
        log.debug("Attempting rule-based classification for query: {}", userInput);

        // ========== Check for leading greeting ==========
        Matcher greetingMatcher = LEADING_GREETING.matcher(normalized);

        if (greetingMatcher.find()) {
            // Extract everything remaining after the greeting match
            String remainingText = normalized.substring(greetingMatcher.end()).trim();

            // 80/20 Rule: If nothing substantial follows, it's just a pure greeting
            if (remainingText.length() < 5) {
                log.debug("Rule-based match: GENERAL_GREETING (pure greeting with no substantial trailing content)");
                return new IntentClassificationResponse(
                        RoutingIntent.GENERAL_GREETING,
                        1.0,
                        "Matched pure greeting prefix with no significant trailing content."
                );
            }

            // Fall-through strategy: A greeting was stripped, but complex text remains.
            // Delegate the remaining text to the LLM classifier rather than matching further rules here.
            log.debug("Leading greeting stripped, delegating remaining text to LLM classifier: {}", remainingText);
        }

        // No rules matched - return null to delegate to LLM classifier
        log.debug("No rule-based match found, delegating to LLM classifier");
        return null;
    }
}
