package com.skyshift.cognitiveragengine.ingestion.docling;

import com.skyshift.cognitiveragengine.common.exception.ParseException;
import com.skyshift.cognitiveragengine.ingestion.model.dto.DoclingItem;
import com.skyshift.cognitiveragengine.ingestion.model.enums.DoclingItemSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5, Hop 1: resolves document.json_content.body.children's $ref pointers against the flat
 * texts[]/tables[]/pictures[]/groups[] arrays (Section 13), skipping furniture (Section 0.3/0.4)
 * and recursing into group containers.
 */
class DoclingDocumentParserTest {

    private final DoclingDocumentParser parser = new DoclingDocumentParser();

    @Test
    void parse_resolvesBodyRefsInOrder_andFiltersFurniture() throws ParseException {
        String json = """
            {
              "document": {
                "json_content": {
                  "body": { "children": [
                    { "$ref": "#/texts/0" },
                    { "$ref": "#/texts/1" },
                    { "$ref": "#/texts/2" }
                  ]},
                  "texts": [
                    { "self_ref": "#/texts/0", "label": "section_header", "content_layer": "body", "text": "Coverage", "level": 1, "prov": [{"page_no": 1}] },
                    { "self_ref": "#/texts/1", "label": "page_header", "content_layer": "furniture", "text": "Acme Insurance Co.", "prov": [{"page_no": 1}] },
                    { "self_ref": "#/texts/2", "label": "text", "content_layer": "body", "text": "Coverage details follow.", "prov": [{"page_no": 1}] }
                  ],
                  "tables": [],
                  "pictures": [],
                  "groups": []
                }
              }
            }
            """;

        List<DoclingItem> items = parser.parse(toStream(json));

        assertThat(items).extracting(DoclingItem::text)
            .containsExactly("Coverage", "Coverage details follow.");
        assertThat(items).noneMatch(item -> "furniture".equals(item.contentLayer()));
    }

    @Test
    void parse_recursesIntoGroupChildren_withoutEmittingTheGroupItself() throws ParseException {
        String json = """
            {
              "document": {
                "json_content": {
                  "body": { "children": [
                    { "$ref": "#/groups/0" }
                  ]},
                  "texts": [
                    { "self_ref": "#/texts/0", "label": "list_item", "content_layer": "body", "text": "Item A", "prov": [{"page_no": 2}] },
                    { "self_ref": "#/texts/1", "label": "list_item", "content_layer": "body", "text": "Item B", "prov": [{"page_no": 2}] }
                  ],
                  "tables": [],
                  "pictures": [],
                  "groups": [
                    { "self_ref": "#/groups/0", "label": "list", "content_layer": "body", "children": [
                      { "$ref": "#/texts/0" },
                      { "$ref": "#/texts/1" }
                    ]}
                  ]
                }
              }
            }
            """;

        List<DoclingItem> items = parser.parse(toStream(json));

        assertThat(items).extracting(DoclingItem::text).containsExactly("Item A", "Item B");
        assertThat(items).noneMatch(item -> item.source() == DoclingItemSource.GROUP);
    }

    @Test
    void parse_rendersTableCellsAsMarkdownTable() throws ParseException {
        String json = """
            {
              "document": {
                "json_content": {
                  "body": { "children": [ { "$ref": "#/tables/0" } ]},
                  "texts": [],
                  "tables": [
                    { "self_ref": "#/tables/0", "label": "table", "content_layer": "body", "prov": [{"page_no": 3}],
                      "data": { "table_cells": [
                        { "start_row_offset_idx": 0, "end_row_offset_idx": 1, "start_col_offset_idx": 0, "end_col_offset_idx": 1, "text": "Plan" },
                        { "start_row_offset_idx": 0, "end_row_offset_idx": 1, "start_col_offset_idx": 1, "end_col_offset_idx": 2, "text": "Copay" },
                        { "start_row_offset_idx": 1, "end_row_offset_idx": 2, "start_col_offset_idx": 0, "end_col_offset_idx": 1, "text": "Gold" },
                        { "start_row_offset_idx": 1, "end_row_offset_idx": 2, "start_col_offset_idx": 1, "end_col_offset_idx": 2, "text": "$20" }
                      ]}
                    }
                  ],
                  "pictures": [],
                  "groups": []
                }
              }
            }
            """;

        List<DoclingItem> items = parser.parse(toStream(json));

        assertThat(items).hasSize(1);
        DoclingItem table = items.get(0);
        assertThat(table.isTable()).isTrue();
        assertThat(table.text()).contains("Plan").contains("Copay").contains("Gold").contains("$20");
        assertThat(table.text()).contains("---");
    }

    @Test
    void parse_collectsDistinctPageNumbersFromProv_notJustProvLength() throws ParseException {
        String json = """
            {
              "document": {
                "json_content": {
                  "body": { "children": [ { "$ref": "#/texts/0" }, { "$ref": "#/texts/1" } ]},
                  "texts": [
                    { "self_ref": "#/texts/0", "label": "text", "content_layer": "body", "text": "Same page, two columns",
                      "prov": [{"page_no": 4}, {"page_no": 4}] },
                    { "self_ref": "#/texts/1", "label": "text", "content_layer": "body", "text": "Spans two pages",
                      "prov": [{"page_no": 5}, {"page_no": 6}] }
                  ],
                  "tables": [],
                  "pictures": [],
                  "groups": []
                }
              }
            }
            """;

        List<DoclingItem> items = parser.parse(toStream(json));

        assertThat(items.get(0).pageNumbers()).containsExactly(4);
        assertThat(items.get(1).pageNumbers()).containsExactly(5, 6);
    }

    @Test
    void parse_throwsClearParseException_whenResultStatusIsFailure() {
        // Phase 9 finding: docling-serve can report the outer task as "success" (the worker ran
        // without crashing) while the document conversion itself failed — that only shows up in
        // this nested status/errors field, which json_content being null doesn't make obvious on
        // its own (silently produces zero items otherwise). Real payload observed live.
        String json = """
            {
              "document": { "filename": "test.pdf", "json_content": null },
              "status": "failure",
              "errors": [
                { "component_type": "user_input", "error_message": "URL is not allowed: http://minio:9000/...", "category": "source_unavailable" }
              ]
            }
            """;

        assertThatThrownBy(() -> parser.parse(toStream(json)))
            .isInstanceOf(ParseException.class)
            .hasMessageContaining("URL is not allowed");
    }

    private static ByteArrayInputStream toStream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
