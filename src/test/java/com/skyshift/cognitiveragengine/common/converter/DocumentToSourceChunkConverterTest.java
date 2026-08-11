package com.skyshift.cognitiveragengine.common.converter;

import com.skyshift.cognitiveragengine.qa.model.SourceChunk;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * convertAll() feeds QA's single-shot retrieval (already chunkId-deduped by RRF fusion) and the
 * ReAct-agent paths (Assistant/Claims), where the model can call searchKnowledgeBase once per
 * sub-query - the same chunk can come back from more than one sub-query, so convertAll must dedupe
 * by chunkId itself rather than assuming the caller already has unique chunks.
 */
class DocumentToSourceChunkConverterTest {

    @Test
    void convertAll_duplicateChunkIdAcrossSubQueries_keepsOnlyFirstOccurrence() {
        Document firstSubQueryHit = chunk(1L, 10L, "First mention");
        Document secondSubQueryHit = chunk(1L, 10L, "First mention");
        Document distinctChunk = chunk(2L, 10L, "Different chunk");

        List<SourceChunk> sources = DocumentToSourceChunkConverter.convertAll(
                List.of(firstSubQueryHit, secondSubQueryHit, distinctChunk));

        assertEquals(2, sources.size());
        assertEquals(1L, sources.get(0).chunkId());
        assertEquals(2L, sources.get(1).chunkId());
    }

    @Test
    void convertAll_noDuplicates_returnsAllChunksInOrder() {
        Document a = chunk(1L, 10L, "A");
        Document b = chunk(2L, 10L, "B");
        Document c = chunk(3L, 10L, "C");

        List<SourceChunk> sources = DocumentToSourceChunkConverter.convertAll(List.of(a, b, c));

        assertEquals(3, sources.size());
        assertEquals(1L, sources.get(0).chunkId());
        assertEquals(2L, sources.get(1).chunkId());
        assertEquals(3L, sources.get(2).chunkId());
    }

    @Test
    void convertAll_empty_returnsEmptyList() {
        assertEquals(List.of(), DocumentToSourceChunkConverter.convertAll(List.of()));
    }

    private static Document chunk(Long chunkId, Long documentId, String text) {
        return new Document(text, Map.of(
                "chunkId", chunkId,
                "documentId", documentId,
                "chunkNumber", 1,
                "similarity", 0.9,
                "source", "hybrid"
        ));
    }
}
