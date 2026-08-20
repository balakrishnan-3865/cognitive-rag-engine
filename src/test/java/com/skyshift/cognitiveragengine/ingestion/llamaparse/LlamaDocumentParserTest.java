package com.skyshift.cognitiveragengine.ingestion.llamaparse;

import com.skyshift.cognitiveragengine.common.exception.ParseException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolves LlamaParse's {@code items} expand shape ({@code pages[].items[]}) into a flat ordered
 * list of {@link LlamaItem}s, flattening {@code list} wrapper items into their nested sub-items
 * (never emitting the wrapper itself), and tracking page numbers from each page's
 * {@code page_number}.
 */
class LlamaDocumentParserTest {

    private final LlamaDocumentParser parser = new LlamaDocumentParser();

    @Test
    void parse_resolvesHeadingAndTextItems_inOrder() throws ParseException {
        String json = """
            {
              "items": {
                "pages": [
                  {
                    "page_number": 1,
                    "items": [
                      { "type": "heading", "level": 1, "value": "Coverage" },
                      { "type": "text", "value": "Coverage details follow." }
                    ]
                  }
                ]
              }
            }
            """;

        List<LlamaItem> items = parser.parse(toStream(json));

        assertThat(items).extracting(LlamaItem::text)
            .containsExactly("Coverage", "Coverage details follow.");
        assertThat(items.get(0).isSectionHeader()).isTrue();
        assertThat(items.get(0).level()).isEqualTo(1);
        assertThat(items.get(1).isSectionHeader()).isFalse();
    }

    @Test
    void parse_flattensNestedListItems_inOrder_withoutEmittingTheListWrapperItself() throws ParseException {
        String json = """
            {
              "items": {
                "pages": [
                  {
                    "page_number": 1,
                    "items": [
                      { "type": "list", "ordered": false, "items": [
                        { "type": "text", "value": "Gold: low copay" },
                        { "type": "text", "value": "Silver: moderate copay" }
                      ]}
                    ]
                  }
                ]
              }
            }
            """;

        List<LlamaItem> items = parser.parse(toStream(json));

        assertThat(items).extracting(LlamaItem::text)
            .containsExactly("Gold: low copay", "Silver: moderate copay");
        assertThat(items).allMatch(LlamaItem::isListItem);
        assertThat(items).noneMatch(item -> "list".equals(item.type()));
    }

    @Test
    void parse_buildsTableRowsFromRowsArray() throws ParseException {
        String json = """
            {
              "items": {
                "pages": [
                  {
                    "page_number": 3,
                    "items": [
                      { "type": "table", "rows": [
                        ["Plan", "Copay"],
                        ["Gold", "$20"]
                      ]}
                    ]
                  }
                ]
              }
            }
            """;

        List<LlamaItem> items = parser.parse(toStream(json));

        assertThat(items).hasSize(1);
        LlamaItem table = items.get(0);
        assertThat(table.isTable()).isTrue();
        assertThat(table.tableRows()).containsExactly(
            List.of("Plan", "Copay"),
            List.of("Gold", "$20"));
        assertThat(table.text()).contains("Plan").contains("Copay").contains("Gold").contains("$20").contains("---");
    }

    @Test
    void parse_tracksPageNumberFromEachPagesPageNumberField() throws ParseException {
        String json = """
            {
              "items": {
                "pages": [
                  { "page_number": 1, "items": [ { "type": "text", "value": "Page one item." } ] },
                  { "page_number": 2, "items": [ { "type": "text", "value": "Page two item." } ] }
                ]
              }
            }
            """;

        List<LlamaItem> items = parser.parse(toStream(json));

        assertThat(items.get(0).pageNumbers()).containsExactly(1);
        assertThat(items.get(1).pageNumbers()).containsExactly(2);
    }

    @Test
    void parse_handlesCodeTypeItem_structurallySameShapeAsText() throws ParseException {
        // Verification's Accepted Risk: "code" item type never observed live in a captured
        // sample, but is documented as structurally identical to "text" — hand-written fixture.
        String json = """
            {
              "items": {
                "pages": [
                  {
                    "page_number": 1,
                    "items": [
                      { "type": "code", "value": "print('hello')" }
                    ]
                  }
                ]
              }
            }
            """;

        List<LlamaItem> items = parser.parse(toStream(json));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).text()).isEqualTo("print('hello')");
        assertThat(items.get(0).isSectionHeader()).isFalse();
        assertThat(items.get(0).isTable()).isFalse();
    }

    @Test
    void parse_resolvesLiveCapturedSample_headingsListTableAndPageBoundaries() throws Exception {
        List<LlamaItem> items = parser.parse(sampleStream());

        assertThat(items).extracting(LlamaItem::text).containsExactly(
            "Coverage Summary",
            "Overview",
            "This plan provides coverage for eligible members under the Gold and Silver tiers. Benefits apply after the deductible is met.",
            "Plan Options",
            "Gold: low copay, higher premium",
            "Silver: moderate copay and premium",
            "Bronze: high copay, lowest premium",
            "Copay Table",
            items.get(8).text(),
            "Exclusions",
            "The following services are excluded from coverage under all plan tiers: cosmetic procedures, experimental treatments not approved by the FDA, and services rendered by out-of-network providers without prior authorization."
        );

        assertThat(items.get(8).isTable()).isTrue();
        assertThat(items.get(8).tableRows()).hasSize(4);
        assertThat(items.get(8).pageNumbers()).containsExactly(1);
        assertThat(items.get(9).pageNumbers()).containsExactly(2);
    }

    private InputStream sampleStream() {
        return getClass().getClassLoader().getResourceAsStream("samples/llamaparse-items-sample.json");
    }

    private static ByteArrayInputStream toStream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
