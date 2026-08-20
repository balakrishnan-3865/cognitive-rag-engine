package com.skyshift.cognitiveragengine.ingestion.llamaparse;

import java.util.List;

/**
 * A single resolved item from LlamaParse's {@code items} expand shape
 * ({@code pages[].items[]}). Table items carry their content pre-rendered as Markdown in
 * {@code text} (mirroring {@code DoclingItem}) for the common (under-budget) case, plus the raw
 * row grid in {@code tableRows} (first row is the header) so an oversized table can be split by
 * whole row instead of being emitted as one giant chunk. {@code list} wrapper items from the wire
 * shape never become a {@link LlamaItem} themselves — {@link LlamaDocumentParser} flattens their
 * nested sub-items into {@code list_item}-typed instances instead.
 */
public record LlamaItem(
        String type,
        Integer level,
        String text,
        List<Integer> pageNumbers,
        List<List<String>> tableRows
) {
    public boolean isSectionHeader() {
        return "heading".equals(type);
    }

    public boolean isTable() {
        return "table".equals(type);
    }

    public boolean isListItem() {
        return "list_item".equals(type);
    }
}
