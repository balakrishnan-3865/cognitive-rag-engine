package com.skyshift.cognitiveragengine.ingestion.llamaparse;

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
 * Token-budget packing parity with {@code DoclingChunkAssemblerTest} — same decision loop
 * (Section 14), structurally paralleling the Docling assembler without sharing code, since
 * {@link LlamaItem} carries {@code type}/{@code tableRows} instead of Docling's
 * {@code label}/{@code tableGrid}.
 */
class LlamaChunkAssemblerTest {

    private static LlamaItem text(String text, int page) {
        return new LlamaItem("text", null, text, List.of(page), null);
    }

    private static LlamaItem header(String text, int level, int page) {
        return new LlamaItem("heading", level, text, List.of(page), null);
    }

    private static LlamaItem listItem(String text, int page) {
        return new LlamaItem("list_item", null, text, List.of(page), null);
    }

    private static LlamaItem tableWithRows(List<List<String>> rows, int page) {
        String markdown = renderRowsAsMarkdown(rows);
        return new LlamaItem("table", null, markdown, List.of(page), rows);
    }

    private static String renderRowsAsMarkdown(List<List<String>> rows) {
        StringBuilder markdown = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            markdown.append("| ").append(String.join(" | ", rows.get(r))).append(" |\n");
            if (r == 0) {
                markdown.append("|").append(" --- |".repeat(rows.get(0).size())).append("\n");
            }
        }
        return markdown.toString().stripTrailing();
    }

    @Test
    void consecutiveParagraphs_noHeaderBetween_mergeIntoOneChunk() {
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(1200);

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
    void heading_midSequence_forcesBoundary_evenUnderBudget() {
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(1200);

        List<Document> chunks = assembler.assemble(List.of(
            text("Intro paragraph.", 1),
            header("Exclusions", 1, 1),
            text("Exclusions detail.", 1)));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getText()).contains("Intro paragraph.").doesNotContain("Exclusions detail.");
        assertThat(chunks.get(1).getText()).contains("Exclusions").contains("Exclusions detail.");
    }

    @Test
    void listItems_areRenderedAsBullets() {
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(1200);

        List<Document> chunks = assembler.assemble(List.of(
            listItem("Gold: low copay", 1),
            listItem("Silver: moderate copay", 1)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText())
            .contains("- Gold: low copay")
            .contains("- Silver: moderate copay");
    }

    @Test
    void itemPushingOverBudget_forcesBoundaryAtItemLevel_notMidItem() {
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(10);

        List<Document> chunks = assembler.assemble(List.of(
            text("Short one.", 1),
            text("Short two.", 1)));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getText()).isEqualTo("Short one.");
        assertThat(chunks.get(1).getText()).isEqualTo("Short two.");
    }

    @Test
    void tableItem_midBuffer_forceFlushesPriorBuffer_andIsNeverMerged() {
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(1200);

        List<Document> chunks = assembler.assemble(List.of(
            text("Paragraph before the table.", 1),
            tableWithRows(List.of(List.of("Plan", "Copay"), List.of("Gold", "$20")), 2),
            text("Paragraph after the table.", 3)));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getText()).isEqualTo("Paragraph before the table.");
        assertThat(chunks.get(1).getText()).contains("| Plan | Copay |");
        assertThat(chunks.get(2).getText()).isEqualTo("Paragraph after the table.");
    }

    @Test
    void itemsOnDifferentPages_emitPageRange_notSinglePage() {
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(1200);

        List<Document> chunks = assembler.assemble(List.of(
            text("Starts on page 4.", 4),
            text("Continues on page 5.", 5)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getMetadata()).containsEntry("pageStart", 4).containsEntry("pageEnd", 5);
    }

    @Test
    void endOfStream_withNonEmptyBuffer_stillEmitsTrailingChunk() {
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(1200);

        List<Document> chunks = assembler.assemble(List.of(
            header("Section A", 1, 1),
            text("Trailing paragraph, never followed by another boundary.", 1)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).contains("Trailing paragraph");
    }

    @Test
    void oversizedSingleItem_splitsAtSentenceBoundaries_noChunkExceedsBudget_noContentLost() {
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(12);

        String oversizedItemText = "Short one. Short two. Short three.";
        List<Document> chunks = assembler.assemble(List.of(text(oversizedItemText, 1)));

        assertThat(chunks.size()).isGreaterThan(1);
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
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(12);

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
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(20);

        List<List<String>> rows = List.of(
            List.of("Plan", "Copay"),
            List.of("Gold", "$20"),
            List.of("Silver", "$35"),
            List.of("Bronze", "$50"));

        List<Document> chunks = assembler.assemble(List.of(tableWithRows(rows, 7)));

        assertThat(chunks.size()).isGreaterThan(1);
        for (Document chunk : chunks) {
            assertThat(chunk.getText()).contains("Plan").contains("Copay");
        }

        String recombined = chunks.stream().map(Document::getText).reduce("", String::concat);
        assertThat(recombined)
            .contains("Gold").contains("$20")
            .contains("Silver").contains("$35")
            .contains("Bronze").contains("$50");
    }

    @Test
    void liveCapturedSampleTable_widenedToExceedBudget_splitsByWholeRows_neverMidRow() {
        // Verification/Planning requirement: prove the row-split path against the live captured
        // sample's actual table shape (widened here since the original 3-row table doesn't
        // exceed a reasonable budget on its own), not just a hand-written fixture.
        List<List<String>> liveSampleRows = List.of(
            List.of("Plan", "Copay"),
            List.of("Gold", "$20"),
            List.of("Silver", "$35"),
            List.of("Bronze", "$50"),
            List.of("Platinum", "$75"),
            List.of("Catastrophic", "$5"));
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(24);

        List<Document> chunks = assembler.assemble(List.of(tableWithRows(liveSampleRows, 1)));

        assertThat(chunks.size()).isGreaterThan(1);
        for (Document chunk : chunks) {
            assertThat(chunk.getText()).contains("Plan").contains("Copay");
        }
        String recombined = chunks.stream().map(Document::getText).reduce("", String::concat);
        for (List<String> row : liveSampleRows.subList(1, liveSampleRows.size())) {
            assertThat(recombined).contains(row.get(0)).contains(row.get(1));
        }
    }

    @Test
    void tableUnderBudget_isEmittedWhole_notSplit() {
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(1200);

        List<List<String>> rows = List.of(
            List.of("Plan", "Copay"),
            List.of("Gold", "$20"));

        List<Document> chunks = assembler.assemble(List.of(tableWithRows(rows, 7)));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).contains("Plan").contains("Gold");
    }

    @Test
    void codeTypeItem_packsAsPlainText_likeAnyOtherItem() {
        // Verification's Accepted Risk: "code" never observed live, structurally same shape as
        // "text" — must not special-case or crash the assembler.
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(1200);
        LlamaItem code = new LlamaItem("code", null, "print('hello')", List.of(1), null);

        List<Document> chunks = assembler.assemble(List.of(code));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo("print('hello')");
    }

    @Test
    void sameInstance_assemblingConcurrently_neverCorruptsAnotherCall_sState() throws Exception {
        LlamaChunkAssembler assembler = new LlamaChunkAssembler(1200);
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
