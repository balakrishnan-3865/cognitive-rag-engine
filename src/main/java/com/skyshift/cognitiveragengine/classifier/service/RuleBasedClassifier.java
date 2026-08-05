package com.skyshift.cognitiveragengine.classifier.service;

import com.skyshift.cognitiveragengine.classifier.model.dto.IntentClassificationResponse;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Rule-based intent classifier using boundary-stripped regex patterns.
 * Provides fast, cost-free intent identification for common patterns.
 *
 * Two-pass approach:
 * Pass 1: Check for leading greeting only. If found with minimal trailing text → GENERAL_GREETING.
 *         If greeting found with substantial text → strip and continue to Pass 2.
 * Pass 2: Check remaining text for structured patterns (claim IDs).
 *
 * Returns null if no rules match to delegate to LLM classifier.
 */
@Slf4j
@Component
public class RuleBasedClassifier {

    // PASS 1: Leading greeting pattern - matches at start of normalized input
    private static final Pattern LEADING_GREETING = Pattern.compile(
            "^(hello|hi|hey|goodbye|bye|greetings|howdy)\\s*[,.]?\\s*",
            Pattern.CASE_INSENSITIVE
    );

    // PASS 2: Claim ID pattern - structured identifier format
    private static final Pattern CLAIM_ID_PATTERN = Pattern.compile(
            "\\bCLM-?\\d{3,}\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Classify a query using rule-based regex patterns with two-pass approach.
     *
     * Pass 1: Strip leading greeting. If nothing substantial remains → GENERAL_GREETING.
     *         Otherwise, continue to Pass 2 with remaining text.
     * Pass 2: Check remaining text for claim ID patterns.
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

        // ========== PASS 1: Check for leading greeting ==========
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
            // Update reference pointer to evaluate the rest against other rules
            log.debug("Leading greeting stripped, evaluating remaining text: {}", remainingText);
            normalized = remainingText;
        }

        // ========== PASS 2: Check the remaining sanitized text for structured patterns ==========
        if (CLAIM_ID_PATTERN.matcher(normalized).find()) {
            log.debug("Rule-based match: CLAIM_STATUS_TOOL (found claim ID pattern)");
            return new IntentClassificationResponse(
                    RoutingIntent.CLAIM_STATUS_TOOL,
                    1.0,
                    "Found claim ID pattern within the evaluated input block."
            );
        }

        // No rules matched - return null to delegate to LLM classifier
        log.debug("No rule-based match found, delegating to LLM classifier");
        return null;
    }
}
