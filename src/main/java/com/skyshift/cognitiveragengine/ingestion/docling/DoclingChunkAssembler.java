package com.skyshift.cognitiveragengine.ingestion.docling;

import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.BreakIterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Phase 5, Hop 2 — the stateful chunk assembler (Section 14's decision loop). Consumes
 * {@link DoclingItem}s one at a time and emits zero-or-more finalized {@link Document}s. Working
 * budget is an estimated token count (chars/4, Section 18), not a character count.
 *
 * <p>This class itself is a stateless, thread-safe Spring singleton — all of Section 14's
 * "state variables" (item buffer, page tracker, hierarchy stack) live in a fresh
 * {@link AssemblyState} created per {@link #assemble} call, not as instance fields. Multiple
 * documents ingest concurrently on Phase 2's virtual-thread executor, so instance-level mutable
 * state here would corrupt across concurrent calls.
 */
@Slf4j
@Component
public class DoclingChunkAssembler {

    private final int tokenBudget;

    public DoclingChunkAssembler(@Value("${docling.max-chunk-size-chars:1200}") int maxChunkSizeChars) {
        this.tokenBudget = Math.max(1, (int) Math.ceil(maxChunkSizeChars / 4.0));
    }

    public List<Document> assemble(List<DoclingItem> items) {
        AssemblyState state = new AssemblyState();
        List<Document> chunks = new ArrayList<>();

        for (DoclingItem item : items) {
            if (item.isTable()) {
                flushIfNonEmpty(state, chunks);
                emitTableChunks(state, chunks, item);
                continue;
            }

            if (item.isSectionHeader()) {
                flushIfNonEmpty(state, chunks);
                updateHierarchy(state, item);
                addToBuffer(state, item);
                continue;
            }

            int itemTokens = estimateTokens(item.text());

            if (itemTokens > tokenBudget) {
                flushIfNonEmpty(state, chunks);
                emitSentenceSplitChunks(state, chunks, item);
                continue;
            }

            if (!state.itemBuffer.isEmpty() && state.currentTokenCount + itemTokens > tokenBudget) {
                flushIfNonEmpty(state, chunks);
            }

            addToBuffer(state, item);
        }

        flushIfNonEmpty(state, chunks);
        return chunks;
    }

    private void addToBuffer(AssemblyState state, DoclingItem item) {
        state.itemBuffer.add(item);
        state.currentTokenCount += estimateTokens(item.text());
        state.pageTracker.addAll(item.pageNumbers());
    }

    private void flushIfNonEmpty(AssemblyState state, List<Document> chunks) {
        if (state.itemBuffer.isEmpty()) {
            return;
        }
        String text = compileMarkdown(state.itemBuffer);
        chunks.add(buildDocument(state, text, List.copyOf(state.pageTracker), "text"));
        state.itemBuffer.clear();
        state.pageTracker.clear();
        state.currentTokenCount = 0;
    }

    private void updateHierarchy(AssemblyState state, DoclingItem headerItem) {
        int level = headerItem.level() != null ? headerItem.level() : 1;
        while (!state.currentHierarchy.isEmpty() && state.currentHierarchy.peekLast().level() >= level) {
            state.currentHierarchy.removeLast();
        }
        state.currentHierarchy.addLast(new HeaderEntry(level, headerItem.text()));
    }

    /**
     * A table under budget is emitted whole (existing behavior). An oversized table is split by
     * whole row only (Section 9: never string-slice a table's grid) — the header row is repeated
     * in every split chunk so each stays self-describing on its own. A single row that alone
     * (with the header) still exceeds budget is accepted as its own oversized chunk rather than
     * mangled mid-row.
     */
    private void emitTableChunks(AssemblyState state, List<Document> chunks, DoclingItem item) {
        List<List<String>> grid = item.tableGrid();
        if (grid == null || grid.size() < 2 || estimateTokens(item.text()) <= tokenBudget) {
            chunks.add(buildDocument(state, item.text(), item.pageNumbers(), "table"));
            return;
        }

        List<String> header = grid.get(0);
        List<List<String>> rows = grid.subList(1, grid.size());

        List<List<String>> group = new ArrayList<>();
        for (List<String> row : rows) {
            List<List<String>> candidate = new ArrayList<>(group);
            candidate.add(row);

            if (!group.isEmpty() && estimateTokens(renderTableMarkdown(header, candidate)) > tokenBudget) {
                chunks.add(buildDocument(state, renderTableMarkdown(header, group), item.pageNumbers(), "table"));
                group = new ArrayList<>(List.of(row));
            } else {
                group = candidate;
            }
        }
        if (!group.isEmpty()) {
            chunks.add(buildDocument(state, renderTableMarkdown(header, group), item.pageNumbers(), "table"));
        }
    }

    private String renderTableMarkdown(List<String> header, List<List<String>> rows) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("| ").append(String.join(" | ", header)).append(" |\n");
        markdown.append("|").append(" --- |".repeat(header.size())).append("\n");
        for (List<String> row : rows) {
            markdown.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return markdown.toString().stripTrailing();
    }

    /**
     * Oversized single item (Section 9): sentence-split via BreakIterator and greedily pack
     * sentences into budget-sized chunks. Never merges with neighboring items (buffer was
     * already flushed by the caller before this runs); a single sentence that alone still
     * exceeds budget is emitted as its own oversized chunk rather than truncated or dropped.
     */
    private void emitSentenceSplitChunks(AssemblyState state, List<Document> chunks, DoclingItem item) {
        List<String> sentences = splitIntoSentences(item.text());
        StringBuilder buf = new StringBuilder();
        int bufTokens = 0;

        for (String sentence : sentences) {
            int sentenceTokens = estimateTokens(sentence);
            if (bufTokens > 0 && bufTokens + sentenceTokens > tokenBudget) {
                chunks.add(buildDocument(state, buf.toString(), item.pageNumbers(), "text"));
                buf.setLength(0);
                bufTokens = 0;
            }
            buf.append(sentence);
            bufTokens += sentenceTokens;
        }

        if (StringUtils.hasText(buf.toString())) {
            chunks.add(buildDocument(state, buf.toString(), item.pageNumbers(), "text"));
        }
    }

    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
        iterator.setText(text);

        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            sentences.add(text.substring(start, end));
        }
        return sentences;
    }

    private String compileMarkdown(List<DoclingItem> items) {
        StringBuilder markdown = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                markdown.append("\n\n");
            }
            markdown.append(renderMarkdown(items.get(i)));
        }
        return markdown.toString();
    }

    private String renderMarkdown(DoclingItem item) {
        if (item.isSectionHeader()) {
            int level = Math.max(1, Math.min(item.level() != null ? item.level() : 1, 6));
            return "#".repeat(level) + " " + item.text();
        }
        if ("list_item".equals(item.label())) {
            return "- " + item.text();
        }
        return item.text();
    }

    private Document buildDocument(AssemblyState state, String text, List<Integer> pages, String itemType) {
        Map<String, Object> metadata = new HashMap<>();
        if (!state.currentHierarchy.isEmpty()) {
            metadata.put("sectionPath", state.currentHierarchy.stream()
                .map(HeaderEntry::text)
                .reduce((a, b) -> a + " > " + b)
                .orElse(""));
        }
        if (!pages.isEmpty()) {
            metadata.put("pageStart", pages.get(0));
            metadata.put("pageEnd", pages.get(pages.size() - 1));
        }
        metadata.put("itemType", itemType);
        metadata.put("sequenceIndex", state.sequenceIndex++);
        return new Document(text, metadata);
    }

    private int estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : (int) Math.ceil(text.length() / 4.0);
    }

    private record HeaderEntry(int level, String text) {}

    /** Per-call mutable state (Section 14) — never shared across concurrent {@link #assemble} calls. */
    private static final class AssemblyState {
        private final List<DoclingItem> itemBuffer = new ArrayList<>();
        private final TreeSet<Integer> pageTracker = new TreeSet<>();
        private final Deque<HeaderEntry> currentHierarchy = new ArrayDeque<>();
        private int currentTokenCount;
        private int sequenceIndex;
    }
}
