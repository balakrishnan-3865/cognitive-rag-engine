package com.skyshift.cognitiveragengine.common.converter;

import com.skyshift.cognitiveragengine.qa.model.SourceChunk;
import org.springframework.ai.document.Document;
import java.util.List;
import java.util.stream.Collectors;

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

	public static List<SourceChunk> convertAll(List<Document> documents) {
		return documents.stream()
				.map(DocumentToSourceChunkConverter::convert)
				.collect(Collectors.toList());
	}
}
