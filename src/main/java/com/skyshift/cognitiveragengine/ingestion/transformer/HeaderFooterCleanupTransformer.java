package com.skyshift.cognitiveragengine.ingestion.transformer;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strips running headers/footers and other boilerplate lines (page numbers, copyright
 * notices, confidentiality stamps) from paginated documents before chunking, so they
 * don't pollute chunk content or dilute embeddings.
 *
 * <p>Two independent signals are used:
 * <ul>
 * <li>A fixed set of regex patterns for boilerplate shapes that are recognizable in
 * isolation (bare page numbers, "Page X of Y", copyright lines).
 * <li>Cross-page repetition: the first/last line of each page is compared across all
 * pages of the document, and any line recurring on a majority of pages is treated as a
 * document-specific running header/footer.
 * </ul>
 */
@Component
public class HeaderFooterCleanupTransformer implements DocumentTransformer {

    private static final List<Pattern> BOILERPLATE_LINE_PATTERNS = List.of(
        Pattern.compile("\\d{1,4}"),
        Pattern.compile("(?i)page\\s+\\d+(\\s*(of|/)\\s*\\d+)?"),
        Pattern.compile("(?i)(\\u00a9|\\(c\\))\\s*\\d{0,4}.*"),
        Pattern.compile("(?i).*all rights reserved.*"),
        Pattern.compile("(?i)(confidential|draft|internal use only).*")
    );

    private static final Pattern LINE_SPLIT = Pattern.compile("\r\n|\r|\n");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    // Repetition detection needs enough pages to distinguish a real running
    // header/footer from a short document whose only page's first/last line
    // would otherwise be flagged after appearing "once out of one".
    private static final int MIN_PAGES_FOR_REPETITION_DETECTION = 3;
    private static final double REPETITION_THRESHOLD = 0.5;

    @Override
    public List<Document> apply(List<Document> documents) {
        Map<String, Integer> boundaryLineFrequency = Map.of();
        int recurrenceCutoff = Integer.MAX_VALUE;

        if (documents.size() >= MIN_PAGES_FOR_REPETITION_DETECTION) {
            boundaryLineFrequency = countBoundaryLineOccurrences(documents);
            recurrenceCutoff = (int) Math.ceil(documents.size() * REPETITION_THRESHOLD);
        }

        List<Document> cleaned = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            cleaned.add(cleanDocument(doc, boundaryLineFrequency, recurrenceCutoff));
        }
        return cleaned;
    }

    private Map<String, Integer> countBoundaryLineOccurrences(List<Document> documents) {
        Map<String, Integer> frequency = new HashMap<>();
        for (Document doc : documents) {
            for (String boundaryLine : boundaryLines(doc.getText())) {
                frequency.merge(normalize(boundaryLine), 1, Integer::sum);
            }
        }
        return frequency;
    }

    private Document cleanDocument(Document doc, Map<String, Integer> boundaryLineFrequency, int recurrenceCutoff) {
        String text = doc.getText();
        if (!StringUtils.hasText(text)) {
            return doc;
        }

        List<String> lines = new ArrayList<>(List.of(LINE_SPLIT.split(text, -1)));
        int firstIndex = firstNonBlankIndex(lines);
        int lastIndex = lastNonBlankIndex(lines);

        List<String> retained = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            boolean isBoundaryLine = (i == firstIndex || i == lastIndex) && StringUtils.hasText(trimmed);
            boolean isRecurringBoundary = isBoundaryLine
                && boundaryLineFrequency.getOrDefault(normalize(trimmed), 0) >= recurrenceCutoff;

            if (isRecurringBoundary || matchesBoilerplatePattern(trimmed)) {
                continue;
            }
            retained.add(line);
        }

        String cleanedText = String.join("\n", retained).trim();
        return doc.mutate().text(cleanedText).build();
    }

    private List<String> boundaryLines(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        List<String> lines = List.of(LINE_SPLIT.split(text, -1));
        int first = firstNonBlankIndex(lines);
        int last = lastNonBlankIndex(lines);

        List<String> boundaries = new ArrayList<>(2);
        if (first >= 0) {
            boundaries.add(lines.get(first).trim());
        }
        if (last >= 0 && last != first) {
            boundaries.add(lines.get(last).trim());
        }
        return boundaries;
    }

    private int firstNonBlankIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (StringUtils.hasText(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private int lastNonBlankIndex(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (StringUtils.hasText(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean matchesBoilerplatePattern(String trimmedLine) {
        if (!StringUtils.hasText(trimmedLine)) {
            return false;
        }
        return BOILERPLATE_LINE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(trimmedLine).matches());
    }

    private String normalize(String line) {
        return DIGITS.matcher(line.trim().toLowerCase()).replaceAll("#");
    }
}