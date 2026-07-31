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

    @Test
    void rejoinsWordsBrokenByPdfLineWrapWhilePreservingListStructure() {
        ChunkingService chunkingService = new ChunkingService(5000, 200);

        String text = "21. Hospitalization or Hospitalized means admission in a Hospital for a minimum period of 24 consecutive In- \n"
            + "patient Care hours except for specified procedures/treatments, where such admission could be for a period \n"
            + "of less than 24 consecutive hours. \n"
            + "22. Illness means a sickness or disease or pathological condition leading to the impairment of normal physiological \n"
            + "function and requires medical treatment. \n"
            + "a) Acute condition- Acute condition is a disease, illness or injury that is likely to respond quickly to treatment \n"
            + "which aims to return the person to his or her state of health immediately before suffering the disease/ \n"
            + "illness/injury which leads to full recovery. \n"
            + "b) Chronic condition- A chronic condition is defined as a disease, illness, or injury that has one or more of \n"
            + "the following characteristics: \n"
            + "i) it needs ongoing or long-term monitoring through consultations, examinations, checkups, and /or tests \n"
            + "ii) it needs ongoing or long-term control or relief of symptoms \n"
            + "iii) it requires rehabilitation for the patient or for the patient to be specially trained to cope with it \n"
            + "iv) it continues indefinitely \n"
            + "v) it recurs or is likely to recur ";

        Document document = new Document(text, java.util.Map.of("source", "test.txt", "page_number", "1"));

        List<DocumentChunkEntity> chunks = chunkingService.chunk(List.of(document), 1L, 1L);
        assertThat(chunks).hasSize(1);
        String chunkText = chunks.get(0).getChunkText();

        // Mid-word wrap noise is gone: the hyphenated word-wrap is rejoined.
        assertThat(chunkText).contains("24 consecutive Inpatient Care hours");
        assertThat(chunkText).doesNotContain("In-\n");
        // Plain word-wraps (no hyphen) are joined with a single space.
        assertThat(chunkText).contains("normal physiological function and requires medical treatment.");
        // Enumerated list markers still start their own line.
        assertThat(chunkText).contains("characteristics:\ni) it needs");
        assertThat(chunkText).contains("tests\nii) it needs");
        assertThat(chunkText).contains("symptoms\niii) it requires");
    }
}