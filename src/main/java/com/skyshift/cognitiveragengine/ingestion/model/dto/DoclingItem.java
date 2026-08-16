package com.skyshift.cognitiveragengine.ingestion.model.dto;

import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingItemSource;

import java.util.List;

/**
 * A single resolved item from Docling's body ref-tree (Hop 1 output — see
 * docs/overview/docling-parsing/DOCLING_STREAMING_INGESTION.md Section 13). Table items carry
 * their content pre-rendered as Markdown in {@code text} (Section 14: "tables render as Markdown
 * tables") for the common (under-budget) case, plus the raw row grid in {@code tableGrid}
 * (first row is the header) so an oversized table can be split by whole row instead of being
 * emitted as one giant chunk. Non-table items leave {@code tableGrid} null.
 */
public record DoclingItem(
        String selfRef,
        DoclingItemSource source,
        String label,
        Integer level,
        String text,
        String contentLayer,
        List<Integer> pageNumbers,
        List<List<String>> tableGrid
) {
    public boolean isSectionHeader() {
        return "section_header".equals(label);
    }

    public boolean isTable() {
        return source == DoclingItemSource.TABLE;
    }
}
