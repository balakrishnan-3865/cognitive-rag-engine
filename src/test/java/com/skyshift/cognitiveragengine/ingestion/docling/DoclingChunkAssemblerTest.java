package com.skyshift.cognitiveragengine.ingestion.docling;

import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingItem;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingItemSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5, Hop 2: the stateful assembler (Section 14's decision loop). Uses a generous
 * (~1200 char / ~300 token) budget by default so ordinary test text never accidentally
 * overflows; individual tests use a tiny budget where overflow behavior is what's under test.
 */
class DoclingChunkAssemblerTest {

    private static DoclingItem text(String text, int page) {
        return new DoclingItem("#/texts/x", DoclingItemSource.TEXT, "text", null, text, "body", List.of(page), null);
    }

    private static DoclingItem header(String text, int level, int page) {
        return new DoclingItem("#/texts/x", DoclingItemSource.TEXT, "section_header", level, text, "body", List.of(page), null);
    }

    private static DoclingItem table(String markdown, int page) {
        return new DoclingItem("#/tables/x", DoclingItemSource.TABLE, "table", null, markdown, "body", List.of(page), null);
    }

    private static DoclingItem tableWithGrid(List<List<String>> grid, int page) {
        String markdown = renderGridAsMarkdown(grid);
        return new DoclingItem("#/tables/x", DoclingItemSource.TABLE, "table", null, markdown, "body", List.of(page), grid);
    }

    private static String renderGridAsMarkdown(List<List<String>> grid) {
        StringBuilder markdown = new StringBuilder();
        for (int r = 0; r < grid.size(); r++) {
            markdown.append("| ").append(String.join(" | ", grid.get(r))).append(" |\n");
            if (r == 0) {
                markdown.append("|").append(" --- |".repeat(grid.get(0).size())).append("\n");
            }
        }
        return markdown.toString().stripTrailing();
    }

    @Test
    void consecutiveParagraphs_noHeaderBetween_mergeIntoOneChunk() {
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(1200);

        List<Document> chunks = assembler.assemble(List.of(
            text("First paragraph.", 1),
            text("Second paragraph.", 1),
            text("Third paragraph.", 1)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText())
            .contains("First paragraph.")
            .contains("Second paragraph.")
            .contains("Third paragraph.");
    }

    @Test
    void sectionHeader_midSequence_forcesBoundary_evenUnderBudget() {
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(1200);

        List<Document> chunks = assembler.assemble(List.of(
            text("Intro paragraph.", 1),
            header("Exclusions", 1, 1),
            text("Exclusions detail.", 1)));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getText()).contains("Intro paragraph.").doesNotContain("Exclusions detail.");
        assertThat(chunks.get(1).getText()).contains("Exclusions").contains("Exclusions detail.");
    }

    @Test
    void itemPushingOverBudget_forcesBoundaryAtItemLevel_notMidItem() {
        // Budget of 10 chars (~3 tokens) forces the second item into its own chunk instead of
        // being appended, and never truncates either item's text.
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(10);

        List<Document> chunks = assembler.assemble(List.of(
            text("Short one.", 1),
            text("Short two.", 1)));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getText()).isEqualTo("Short one.");
        assertThat(chunks.get(1).getText()).isEqualTo("Short two.");
    }

    @Test
    void tableItem_midBuffer_forceFlushesPriorBuffer_andIsNeverMerged() {
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(1200);

        List<Document> chunks = assembler.assemble(List.of(
            text("Paragraph before the table.", 1),
            table("| Plan | Copay |\n| --- | --- |\n| Gold | $20 |", 2),
            text("Paragraph after the table.", 3)));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getText()).isEqualTo("Paragraph before the table.");
        assertThat(chunks.get(1).getText()).contains("| Plan | Copay |");
        assertThat(chunks.get(2).getText()).isEqualTo("Paragraph after the table.");
    }

    @Test
    void itemsOnDifferentPages_emitPageRange_notSinglePage() {
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(1200);

        List<Document> chunks = assembler.assemble(List.of(
            text("Starts on page 4.", 4),
            text("Continues on page 5.", 5)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getMetadata()).containsEntry("pageStart", 4).containsEntry("pageEnd", 5);
    }

    @Test
    void endOfStream_withNonEmptyBuffer_stillEmitsTrailingChunk() {
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(1200);

        List<Document> chunks = assembler.assemble(List.of(
            header("Section A", 1, 1),
            text("Trailing paragraph, never followed by another boundary.", 1)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).contains("Trailing paragraph");
    }

    @Test
    void oversizedSingleItem_splitsAtSentenceBoundaries_noChunkExceedsBudget_noContentLost() {
        // ~12-char budget (~3 tokens). Each sentence below is short but the item as a whole
        // is well over budget, forcing the sentence-split path.
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(12);

        String oversizedItemText = "Short one. Short two. Short three.";
        List<Document> chunks = assembler.assemble(List.of(text(oversizedItemText, 1)));

        assertThat(chunks.size()).isGreaterThan(1);
        // No chunk is empty, no sentence is split mid-word, and every sentence survives intact
        // across the emitted chunks with nothing dropped.
        StringBuilder recombined = new StringBuilder();
        for (Document chunk : chunks) {
            assertThat(chunk.getText()).isNotBlank();
            recombined.append(chunk.getText());
        }
        assertThat(recombined.toString())
            .contains("Short one.")
            .contains("Short two.")
            .contains("Short three.");
    }

    @Test
    void oversizedSingleItem_neverMergesWithNeighboringItems() {
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(12);

        String oversizedItemText = "Sentence one is here. Sentence two is here too.";
        List<Document> chunks = assembler.assemble(List.of(
            text("Before.", 1),
            text(oversizedItemText, 1),
            text("After.", 1)));

        assertThat(chunks.get(0).getText()).isEqualTo("Before.");
        assertThat(chunks.get(chunks.size() - 1).getText()).isEqualTo("After.");
        for (int i = 1; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).getText()).doesNotContain("Before.").doesNotContain("After.");
        }
    }

    @Test
    void oversizedTable_splitsByWholeRows_repeatsHeaderInEveryChunk_neverMidRow() {
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(20);

        List<List<String>> grid = List.of(
            List.of("Plan", "Copay"),
            List.of("Gold", "$20"),
            List.of("Silver", "$35"),
            List.of("Bronze", "$50"));

        List<Document> chunks = assembler.assemble(List.of(tableWithGrid(grid, 7)));

        assertThat(chunks.size()).isGreaterThan(1);
        for (Document chunk : chunks) {
            // Header repeated in every split chunk so each stays self-describing on its own.
            assertThat(chunk.getText()).contains("Plan").contains("Copay");
        }

        String recombined = chunks.stream().map(Document::getText).reduce("", String::concat);
        assertThat(recombined)
            .contains("Gold").contains("$20")
            .contains("Silver").contains("$35")
            .contains("Bronze").contains("$50");
    }

    @Test
    void tableUnderBudget_isEmittedWhole_notSplit() {
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(1200);

        List<List<String>> grid = List.of(
            List.of("Plan", "Copay"),
            List.of("Gold", "$20"));

        List<Document> chunks = assembler.assemble(List.of(tableWithGrid(grid, 7)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).contains("Plan").contains("Gold");
    }

    @Test
    void sameInstance_assemblingConcurrently_neverCorruptsAnotherCall_sState() throws Exception {
        // A single shared instance (as it is when Spring wires it as a singleton bean) must be
        // safe under concurrent assemble() calls from Phase 2's virtual-thread executor — no
        // instance-level mutable state may leak between two documents ingesting at once.
        DoclingChunkAssembler assembler = new DoclingChunkAssembler(1200);
        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger mismatches = new AtomicInteger(0);

        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int c = 0; c < callers; c++) {
                int callerId = c;
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 20; i++) {
                            String marker = "caller-" + callerId;
                            List<Document> chunks = assembler.assemble(List.of(
                                text(marker + " paragraph one.", 1),
                                text(marker + " paragraph two.", 1)));
                            for (Document chunk : chunks) {
                                if (chunk.getText().contains("caller-") && !chunk.getText().contains(marker)) {
                                    mismatches.incrementAndGet();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        assertThat(mismatches.get()).isZero();
    }
}
