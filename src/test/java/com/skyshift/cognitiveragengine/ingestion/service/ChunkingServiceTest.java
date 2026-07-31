package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingServiceTest {

    @Test
    void overlapNeverSplitsAWordMidToken() {
        ChunkingService chunkingService = new ChunkingService(300, 80);

        // Sentences engineered so a fixed-character cut at "length - overlapChars"
        // would land inside "extraordinarily".
        String text = "Sentence number one is short. Sentence number two is also short. "
            + "This sentence ends with the word extraordinarily. "
            + "A fresh sentence begins right after that word and continues on for a while to "
            + "push the chunk past the configured maximum size so a split is forced here soon. "
            + "Another sentence follows to guarantee the splitter must break the text into "
            + "multiple chunks for this test to be meaningful at all.";

        Document document = new Document(text, java.util.Map.of("source", "test.txt", "page_number", "1"));

        List<DocumentChunkEntity> chunks = chunkingService.chunk(List.of(document), 1L, 1L);

        assertThat(chunks.size()).isGreaterThan(1);

        for (DocumentChunkEntity chunk : chunks) {
            String chunkText = chunk.getChunkText();
            assertThat(chunkText).doesNotContain("extraordinar ");
            assertThat(chunkText).doesNotContain(" xtraordinarily");
        }
    }

    @Test
    void overlapRetainsWholeTrailingSentenceOfPreviousChunk() {
        ChunkingService chunkingService = new ChunkingService(120, 60);

        String text = "First sentence sets the scene for the whole paragraph right here. "
            + "Second sentence adds a critical detail that the next chunk must not lose. "
            + "Third sentence pushes the text past the max chunk size so a split has to occur "
            + "somewhere before this final trailing sentence is reached in the source text.";

        Document document = new Document(text, java.util.Map.of("source", "test.txt", "page_number", "1"));

        List<DocumentChunkEntity> chunks = chunkingService.chunk(List.of(document), 1L, 1L);

        assertThat(chunks.size()).isGreaterThan(1);

        DocumentChunkEntity secondChunk = chunks.get(1);
        assertThat(secondChunk.getMetadataJson()).contains("\"hasOverlap\":true");

        // The overlap must start at a sentence boundary (capital letter after the
        // implicit sentence break), never mid-word or mid-sentence.
        String firstWord = secondChunk.getChunkText().trim().split("\\s+")[0];
        assertThat(Character.isUpperCase(firstWord.charAt(0))).isTrue();
    }
}