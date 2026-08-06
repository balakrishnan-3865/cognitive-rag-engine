package com.skyshift.cognitiveragengine.tools;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.skyshift.cognitiveragengine.assistant.config.AssistantProperties;
import com.skyshift.cognitiveragengine.common.observability.ObservabilityProperties;
import com.skyshift.cognitiveragengine.qa.service.HybridChunkRetrievalService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Retrieval-only tool for the assistant's ReAct agent: it returns raw evidence chunks and lets
 * the agent's own LLM call synthesize the answer. groupId is never a tool argument the model can
 * set - it is bound server-side via {@link ToolContext} (see GROUP_ID_CONTEXT_KEY) so a prompt
 * cannot talk the agent into querying a different group's corpus.
 */
@Slf4j
@Component
public class KnowledgeBaseTool {

    public static final String GROUP_ID_CONTEXT_KEY = "assistant.groupId";
    public static final String RESULT_HOLDER_CONTEXT_KEY = "assistant.retrievedDocuments";

    private static final String TOOL_NAME = "searchKnowledgeBase";
    private static final String OBSERVATION_NAME = "tool_call";

    private final HybridChunkRetrievalService hybridChunkRetrievalService;
    private final AssistantProperties assistantProperties;
    private final ObservationRegistry observationRegistry;
    private final ObservabilityProperties observabilityProperties;

    public KnowledgeBaseTool(
            HybridChunkRetrievalService hybridChunkRetrievalService,
            AssistantProperties assistantProperties,
            ObservationRegistry observationRegistry,
            ObservabilityProperties observabilityProperties
    ) {
        this.hybridChunkRetrievalService = hybridChunkRetrievalService;
        this.assistantProperties = assistantProperties;
        this.observationRegistry = observationRegistry;
        this.observabilityProperties = observabilityProperties;
    }

    @Tool(description = "Search the internal knowledge base for chunks relevant to a question. " +
            "Use this whenever the answer requires information from the document corpus rather than general knowledge.")
    public String searchKnowledgeBase(
            @ToolParam(description = "A focused search query capturing what needs to be found") String query,
            ToolContext toolContext
    ) {
        // AgentToolNode calls MethodToolCallback.call() directly on the graph's calling thread
        // for sequential (single-tool-call) turns, so no context-propagation wrapping is needed here.
        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .lowCardinalityKeyValue("gen_ai.operation.name", "framework")
                .lowCardinalityKeyValue("gen_ai.system", "spring_ai")
                .lowCardinalityKeyValue("spring.ai.kind", "tool_call")
                .lowCardinalityKeyValue("spring.ai.tool.definition.name", TOOL_NAME);

        return observation.observe(() -> doSearch(observation, query, toolContext));
    }

    private String doSearch(Observation observation, String query, ToolContext toolContext) {
        Long groupId = ToolContextHelper.getMetadata(toolContext, GROUP_ID_CONTEXT_KEY, Long.class)
                .orElseThrow(() -> new IllegalStateException("groupId missing from tool context"));

        log.debug("Assistant tool searching knowledge base: query='{}', groupId={}", query, groupId);

        if (observabilityProperties.isCaptureContent()) {
            observation.highCardinalityKeyValue("spring.ai.tool.call.arguments", query);
        }

        List<Document> documents = hybridChunkRetrievalService
                .retrieveRelevantChunks(query, groupId, assistantProperties.getTopKDefault())
                .documents();

        recordRetrievedDocuments(toolContext, documents);
        observation.lowCardinalityKeyValue("retrieved_chunk_count", String.valueOf(documents.size()));

        String result = documents.isEmpty()
                ? "No relevant knowledge base chunks were found for this query."
                : IntStream.range(0, documents.size())
                        .mapToObj(i -> "[%d] %s".formatted(i + 1, documents.get(i).getText()))
                        .collect(Collectors.joining("\n\n"));

        if (observabilityProperties.isCaptureContent()) {
            observation.highCardinalityKeyValue("spring.ai.tool.call.result", result);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private void recordRetrievedDocuments(ToolContext toolContext, List<Document> documents) {
        ToolContextHelper.getMetadata(toolContext, RESULT_HOLDER_CONTEXT_KEY, List.class)
                .ifPresent(holder -> ((List<Document>) holder).addAll(documents));
    }
}