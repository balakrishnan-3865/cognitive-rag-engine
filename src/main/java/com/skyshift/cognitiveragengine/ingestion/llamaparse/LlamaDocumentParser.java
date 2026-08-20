package com.skyshift.cognitiveragengine.ingestion.llamaparse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skyshift.cognitiveragengine.common.exception.ParseException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves LlamaParse's {@code items} expand result ({@code pages[].items[]}) into an ordered
 * flat list of typed {@link LlamaItem}s, mirroring {@code DoclingDocumentParser}'s role for the
 * Docling path (structurally paralleling it, not sharing code — LlamaParse's wire shape is a flat
 * per-page item array, not Docling's {@code $ref} tree). {@code list} items are pure containers:
 * never emitted themselves, only their nested sub-items, flattened in page order as
 * {@code list_item}-typed instances.
 */
@Component
public class LlamaDocumentParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<LlamaItem> parse(InputStream resultStream) throws ParseException {
        try {
            JsonNode root = objectMapper.readTree(resultStream);
            JsonNode pages = root.path("pages");

            List<LlamaItem> items = new ArrayList<>();
            if (pages.isArray()) {
                for (JsonNode page : pages) {
                    int pageNumber = page.path("page_number").asInt();
                    resolveItems(page.path("items"), pageNumber, items);
                }
            }
            return items;
        } catch (IOException e) {
            throw new ParseException("Failed to parse LlamaParse result JSON", e);
        }
    }

    private void resolveItems(JsonNode itemsNode, int pageNumber, List<LlamaItem> out) {
        if (!itemsNode.isArray()) {
            return;
        }

        for (JsonNode item : itemsNode) {
            String type = item.path("type").asText("");

            if ("list".equals(type)) {
                // Pure container — never emitted itself, only its nested sub-items matter.
                for (JsonNode subItem : item.path("items")) {
                    out.add(toLlamaItem("list_item", null, subItem, pageNumber));
                }
                continue;
            }

            Integer level = item.hasNonNull("level") ? item.path("level").asInt() : null;
            out.add(toLlamaItem(type, level, item, pageNumber));
        }
    }

    private LlamaItem toLlamaItem(String type, Integer level, JsonNode node, int pageNumber) {
        if ("table".equals(type)) {
            List<List<String>> rows = buildTableRows(node.path("rows"));
            return new LlamaItem(type, level, renderRowsAsMarkdown(rows), List.of(pageNumber), rows);
        }
        String text = node.path("value").asText("");
        return new LlamaItem(type, level, text, List.of(pageNumber), null);
    }

    private List<List<String>> buildTableRows(JsonNode rowsNode) {
        if (!rowsNode.isArray()) {
            return List.of();
        }
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : rowsNode) {
            List<String> cells = new ArrayList<>();
            for (JsonNode cell : row) {
                cells.add(cell.asText(""));
            }
            rows.add(List.copyOf(cells));
        }
        return rows;
    }

    private String renderRowsAsMarkdown(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        int colCount = rows.get(0).size();
        StringBuilder markdown = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            markdown.append("| ").append(String.join(" | ", rows.get(r))).append(" |\n");
            if (r == 0) {
                markdown.append("|").append(" --- |".repeat(colCount)).append("\n");
            }
        }
        return markdown.toString().stripTrailing();
    }
}
