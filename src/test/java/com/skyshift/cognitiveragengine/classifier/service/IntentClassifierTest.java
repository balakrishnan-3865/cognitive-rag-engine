package com.skyshift.cognitiveragengine.classifier.service;

import com.skyshift.cognitiveragengine.classifier.model.dto.IntentClassificationResponse;
import com.skyshift.cognitiveragengine.classifier.model.enums.RoutingIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link IntentClassifier}'s two-pass classification, including its LLM-failure fallback,
 * which now defaults to {@code AGENT_QUERY} instead of the removed {@code POLICY_DOCUMENT_RAG}
 * (docs/spec.md, "Downstream Impact").
 */
@ExtendWith(MockitoExtension.class)
class IntentClassifierTest {

    private static final String QUERY = "What is a deductible?";

    @Mock
    private RuleBasedClassifier ruleBasedClassifier;
    @Mock
    private ChatClient intentClassificationChatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private final PromptTemplate promptTemplate = new PromptTemplate("Classify: {query}");

    private IntentClassifier classifier() {
        return new IntentClassifier(ruleBasedClassifier, intentClassificationChatClient, promptTemplate);
    }

    @Test
    void ruleMatch_shortCircuitsWithoutCallingLlm() {
        IntentClassificationResponse ruleResult = new IntentClassificationResponse(RoutingIntent.GENERAL_GREETING, 1.0, "greeting");
        when(ruleBasedClassifier.matchRules("Hello")).thenReturn(ruleResult);

        IntentClassificationResponse response = classifier().classify("Hello");

        assertEquals(ruleResult, response);
        verify(intentClassificationChatClient, never()).prompt(anyString());
    }

    @Test
    void ruleMiss_delegatesToLlmClassifier() {
        when(ruleBasedClassifier.matchRules(QUERY)).thenReturn(null);
        IntentClassificationResponse llmResult = new IntentClassificationResponse(RoutingIntent.AGENT_QUERY, 0.85, "policy question");
        when(intentClassificationChatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(IntentClassificationResponse.class)).thenReturn(llmResult);

        IntentClassificationResponse response = classifier().classify(QUERY);

        assertEquals(llmResult, response);
    }

    @Test
    void llmClassificationThrows_defaultsToAgentQuery() {
        when(ruleBasedClassifier.matchRules(QUERY)).thenReturn(null);
        when(intentClassificationChatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(IntentClassificationResponse.class)).thenThrow(new RuntimeException("LLM unavailable"));

        IntentClassificationResponse response = classifier().classify(QUERY);

        assertEquals(RoutingIntent.AGENT_QUERY, response.intent());
        assertEquals(0.0, response.confidence(), 0.0001);
    }
}
