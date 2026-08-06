package com.skyshift.cognitiveragengine.workflows.claims.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.skyshift.cognitiveragengine.classifier.model.dto.IntentClassificationResponse;
import com.skyshift.cognitiveragengine.classifier.service.IntentClassifier;
import com.skyshift.cognitiveragengine.workflows.claims.state.WorkflowStateKeys;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/** Classifies the incoming query's routing intent via the existing two-pass {@link IntentClassifier}. */
@Slf4j
public class IntentCheckNode implements NodeAction {

    public static final String NAME = "intent_check";

    private final IntentClassifier intentClassifier;

    public IntentCheckNode(IntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String originalQuery = state.value(WorkflowStateKeys.ORIGINAL_QUERY, "");

        IntentClassificationResponse classification = intentClassifier.classify(originalQuery);
        log.info("intent_check classified query as {} (confidence={})", classification.intent(), classification.confidence());

        return Map.of(WorkflowStateKeys.ROUTING_INTENT, classification.intent());
    }
}
