package com.skyshift.cognitiveragengine.common.converter;

import com.skyshift.cognitiveragengine.qa.model.SourceChunk;
import org.springframework.ai.document.Document;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DocumentToSourceChunkConverter {

	/**
	 * Converts Spring AI Document metadata to typed SourceChunk.
	 * Metadata values are stored in their proper types (Long, Integer, Double)
	 * to avoid unnecessary string conversions.
	 */
	public static SourceChunk convert(Document doc) {
		return new SourceChunk(
				doc.getText(),
				(Long) doc.getMetadata().get("chunkId"),
				(Long) doc.getMetadata().get("documentId"),
				(Integer) doc.getMetadata().get("chunkNumber"),
				(Double) doc.getMetadata().get("similarity"),
				(String) doc.getMetadata().getOrDefault("source", "unknown")
		);
	}

	/**
	 * Deduped by chunkId, keeping the first occurrence's data - a ReAct agent can call
	 * searchKnowledgeBase once per sub-query, and the same chunk can satisfy more than one, so
	 * the raw document list handed in here is not already unique.
	 */
	public static List<SourceChunk> convertAll(List<Document> documents) {
		Map<Long, SourceChunk> byChunkId = new LinkedHashMap<>();
		for (Document doc : documents) {
			SourceChunk chunk = convert(doc);
			byChunkId.putIfAbsent(chunk.chunkId(), chunk);
		}
		return new ArrayList<>(byChunkId.values());
	}
}
