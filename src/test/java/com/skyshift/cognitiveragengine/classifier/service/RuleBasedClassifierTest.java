package com.skyshift.cognitiveragengine.classifier.service;

import com.skyshift.cognitiveragengine.classifier.model.dto.IntentClassificationResponse;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuleBasedClassifierTest {

    private final RuleBasedClassifier classifier = new RuleBasedClassifier();

    @Test
    void blankInput_returnsOutOfScope() {
        IntentClassificationResponse response = classifier.matchRules("   ");

        assertEquals(RoutingIntent.OUT_OF_SCOPE, response.intent());
    }

    @Test
    void nullInput_returnsOutOfScope() {
        IntentClassificationResponse response = classifier.matchRules(null);

        assertEquals(RoutingIntent.OUT_OF_SCOPE, response.intent());
    }

    @Test
    void pureGreeting_returnsGeneralGreeting() {
        IntentClassificationResponse response = classifier.matchRules("Hello");

        assertEquals(RoutingIntent.GENERAL_GREETING, response.intent());
    }

    @Test
    void greetingWithSubstantialTrailingText_fallsThroughToLlm() {
        IntentClassificationResponse response = classifier.matchRules("Hi, what is my deductible?");

        assertNull(response);
    }

    @Test
    void claimIdMention_fallsThroughToLlm() {
        // No claim-ID fast-path here: claim-status, policy, and multi-source queries all resolve
        // to the same AGENT_QUERY intent, so the LLM classifier is the sole decider for non-greeting input.
        IntentClassificationResponse response = classifier.matchRules("What's the status of claim CLM-123?");

        assertNull(response);
    }

    @Test
    void plainPolicyQuery_fallsThroughToLlm() {
        IntentClassificationResponse response = classifier.matchRules("What is covered under my plan?");

        assertNull(response);
    }
}
