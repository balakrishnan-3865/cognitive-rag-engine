package com.skyshift.cognitiveragengine.ingestion.docling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.common.exception.ParseException;
import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingItem;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingItemSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Phase 5, Hop 1 — resolves Docling's {@code document.json_content.body.children} ref-tree
 * against the flat texts[]/tables[]/pictures[]/groups[] arrays into an ordered list of typed
 * {@link DoclingItem}s (Section 13), filtering furniture items before the assembler ever sees
 * them. Group items are pure containers (never emitted, just recursed into).
 *
 * <p>Ref resolution uses {@link ObjectMapper#readTree} rather than a single forward-only token
 * pass: a {@code $ref} can point anywhere in the document regardless of key order, so true
 * single-pass resolution isn't reliable. This still reads directly off the {@link InputStream}
 * (no intermediate String/byte[] materialization), and the assembler downstream still consumes
 * the resulting items one at a time — the actual bounded-buffering contract Section 14 cares
 * about is preserved at that hop.
 */
@Component
public class DoclingDocumentParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<DoclingItem> parse(InputStream resultStream) throws ParseException {
        try {
            JsonNode root = objectMapper.readTree(resultStream);

            // Phase 9 finding: the outer task can report "success" (the worker ran without
            // crashing) while the document conversion itself failed — that only shows up here,
            // in the result envelope's own status/errors, not the task poll status. Left
            // unchecked, json_content being null silently produces zero items instead of a
            // clear failure.
            if ("failure".equals(root.path("status").asText())) {
                throw new ParseException("Docling conversion failed: " + collectErrorMessages(root.path("errors")));
            }

            JsonNode content = root.path("document").path("json_content");

            List<DoclingItem> items = new ArrayList<>();
            resolveChildren(content, content.path("body").path("children"), items);
            return items;
        } catch (IOException e) {
            throw new ParseException("Failed to parse Docling result JSON", e);
        }
    }

    private String collectErrorMessages(JsonNode errorsNode) {
        if (!errorsNode.isArray() || errorsNode.isEmpty()) {
            return "no error details provided";
        }
        List<String> messages = new ArrayList<>();
        for (JsonNode error : errorsNode) {
            messages.add(error.path("error_message").asText("unknown error"));
        }
        return String.join("; ", messages);
    }

    private void resolveChildren(JsonNode content, JsonNode childrenNode, List<DoclingItem> out) {
        if (!childrenNode.isArray()) {
            return;
        }

        for (JsonNode childRef : childrenNode) {
            String ref = childRef.path("$ref").asText(null);
            if (ref == null) {
                continue;
            }

            JsonNode target = resolveRef(content, ref);
            if (target == null) {
                continue;
            }

            DoclingItemSource source = sourceFromRef(ref);

            if (source == DoclingItemSource.GROUP) {
                // Pure container — never emitted itself, only its children matter.
                resolveChildren(content, target.path("children"), out);
                continue;
            }

            String contentLayer = target.path("content_layer").asText("body");
            if ("furniture".equals(contentLayer)) {
                continue;
            }

            out.add(toDoclingItem(ref, source, target));
        }
    }

    private JsonNode resolveRef(JsonNode content, String ref) {
        String path = ref.startsWith("#/") ? ref.substring(2) : ref;
        String[] parts = path.split("/");
        if (parts.length != 2) {
            return null;
        }

        int index;
        try {
            index = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }

        JsonNode array = content.path(parts[0]);
        return array.isArray() && index < array.size() ? array.get(index) : null;
    }

    private DoclingItemSource sourceFromRef(String ref) {
        if (ref.contains("/tables/")) {
            return DoclingItemSource.TABLE;
        }
        if (ref.contains("/pictures/")) {
            return DoclingItemSource.PICTURE;
        }
        if (ref.contains("/groups/")) {
            return DoclingItemSource.GROUP;
        }
        return DoclingItemSource.TEXT;
    }

    private DoclingItem toDoclingItem(String ref, DoclingItemSource source, JsonNode node) {
        String label = node.path("label").asText("");
        String contentLayer = node.path("content_layer").asText("body");
        Integer level = node.has("level") ? node.path("level").asInt() : null;
        List<Integer> pageNumbers = resolvePageNumbers(node.path("prov"));

        String text;
        List<List<String>> tableGrid = null;
        if (source == DoclingItemSource.TABLE) {
            tableGrid = buildTableGrid(node.path("data"));
            text = renderGridAsMarkdown(tableGrid);
        } else {
            text = node.path("text").asText("");
        }

        return new DoclingItem(ref, source, label, level, text, contentLayer, pageNumbers, tableGrid);
    }

    private List<Integer> resolvePageNumbers(JsonNode provArray) {
        if (!provArray.isArray()) {
            return List.of();
        }
        TreeSet<Integer> pages = new TreeSet<>();
        for (JsonNode prov : provArray) {
            if (prov.has("page_no")) {
                pages.add(prov.path("page_no").asInt());
            }
        }
        return List.copyOf(pages);
    }

    /**
     * Builds the raw row grid (first row is the header) from Docling's {@code table_cells[]}
     * schema, so a caller can either render it whole or split it by row (Section 9: a table
     * must never go through the character/sentence splitter — only a row-aware split is safe).
     */
    private List<List<String>> buildTableGrid(JsonNode data) {
        JsonNode cells = data.path("table_cells");
        if (!cells.isArray() || cells.isEmpty()) {
            return List.of();
        }

        int rowCount = 0;
        int colCount = 0;
        for (JsonNode cell : cells) {
            rowCount = Math.max(rowCount, cell.path("end_row_offset_idx").asInt(0));
            colCount = Math.max(colCount, cell.path("end_col_offset_idx").asInt(0));
        }
        if (rowCount == 0 || colCount == 0) {
            return List.of();
        }

        String[][] grid = new String[rowCount][colCount];
        for (String[] row : grid) {
            java.util.Arrays.fill(row, "");
        }

        for (JsonNode cell : cells) {
            int row = cell.path("start_row_offset_idx").asInt(0);
            int col = cell.path("start_col_offset_idx").asInt(0);
            String cellText = cell.path("text").asText("").replace("|", "\\|").replace("\n", " ");
            if (row < rowCount && col < colCount) {
                grid[row][col] = cellText;
            }
        }

        List<List<String>> result = new ArrayList<>();
        for (String[] row : grid) {
            result.add(List.of(row));
        }
        return result;
    }

    private String renderGridAsMarkdown(List<List<String>> grid) {
        if (grid.isEmpty()) {
            return "";
        }
        int colCount = grid.get(0).size();
        StringBuilder markdown = new StringBuilder();
        for (int r = 0; r < grid.size(); r++) {
            markdown.append("| ").append(String.join(" | ", grid.get(r))).append(" |\n");
            if (r == 0) {
                markdown.append("|").append(" --- |".repeat(colCount)).append("\n");
            }
        }
        return markdown.toString().stripTrailing();
    }
}
