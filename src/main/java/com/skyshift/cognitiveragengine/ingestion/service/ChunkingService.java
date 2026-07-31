package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.ingestion.model.dto.ChunkMetadata;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.BreakIterator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ChunkingService {

    private static final String[] SEPARATORS = {"\n\n", "\n", ". "};
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\x00-\\x08\\x0B-\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern MULTIPLE_SPACES = Pattern.compile(" {2,}");
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{3,}");

    // A line ending in a letter followed by a hyphen, immediately followed by a
    // lowercase-starting line, is treated as a word broken across the PDF's line
    // wrap (e.g. "In-\npatient") rather than a genuine hyphenated break.
    private static final Pattern ENDS_WITH_WRAP_HYPHEN = Pattern.compile(".*\\p{L}-$");
    // A line ending in sentence-terminal punctuation marks a real sentence/clause
    // boundary, so the newline after it is preserved instead of being unwrapped.
    private static final Pattern ENDS_WITH_SENTENCE_TERMINATOR = Pattern.compile(".*[.!?:;][\"')\\]]?$");
    // List/enumeration markers ("a)", "ii)", "3.") start a new logical line even
    // when the previous line has no terminal punctuation, so they are never
    // joined to the line above them.
    private static final Pattern LIST_MARKER_START =
        Pattern.compile("^(\\(?[a-zA-Z]\\)|\\(?[ivxlcdmIVXLCDM]{1,6}\\)|\\(?\\d{1,3}[.)])\\s+.*");

    private final int maxChunkSizeChars;
    private final int overlapChars;

    public ChunkingService(
            @Value("${document.ingestion.chunk-size-chars:1200}") int maxChunkSizeChars,
            @Value("${document.ingestion.chunk-overlap-chars:200}") int overlapChars) {
        this.maxChunkSizeChars = maxChunkSizeChars;
        this.overlapChars = Math.min(overlapChars, maxChunkSizeChars / 2);
    }

    public List<DocumentChunkEntity> chunk(
            List<Document> documents,
            Long documentId,
            Long groupId) {

        log.info("Chunking documents: maxSize={} chars, overlap={} chars",
            maxChunkSizeChars, overlapChars);

        List<DocumentChunkEntity> allChunks = new ArrayList<>();
        int globalChunkIndex = 0;
        LocalDateTime now = LocalDateTime.now();

        for (Document doc : documents) {
            String documentName = doc.getMetadata().getOrDefault("source", "unknown").toString();
            int pageNumber = Integer.parseInt(
                doc.getMetadata().getOrDefault("page_number", "1").toString());

            String cleanedText = clean(doc.getText());
            if (!StringUtils.hasText(cleanedText)) {
                log.warn("Document {} has no valid content after cleaning, skipping", documentName);
                continue;
            }

            List<String> chunks = splitRecursively(cleanedText);
            chunks = mergeSmallChunks(chunks);
            chunks = applyOverlap(chunks);

            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);

                if (!StringUtils.hasText(chunkText)) {
                    continue;
                }

                boolean hasOverlap = i > 0;

                ChunkMetadata metadata = ChunkMetadata.builder()
                    .documentName(documentName)
                    .pageNumber(pageNumber)
                    .length(chunkText.length())
                    .hasOverlap(hasOverlap)
                    .tokenCount(estimateTokenCount(chunkText))
                    .chunkStrategy("recursive-character-sentence-overlap-v2")
                    .chunkIndex(globalChunkIndex)
                    .documentId(documentId)
                    .groupId(groupId)
                    .build();

                DocumentChunkEntity chunkEntity = DocumentChunkEntity.builder()
                        .documentId(documentId)
                        .groupId(groupId)
                        .chunkNumber(globalChunkIndex)
                        .chunkText(chunkText)
                        .metadataJson(metadata.toJson())
                        .createdAt(now).updatedAt(now)
                        .build();

                allChunks.add(chunkEntity);
                globalChunkIndex++;
            }
        }

        log.info("Chunked into {} total chunks", allChunks.size());
        return allChunks;
    }

    private List<String> splitRecursively(String text) {
        return splitByLevel(text, 0);
    }

    private List<String> splitByLevel(String text, int separatorIndex) {
        List<String> chunks = new ArrayList<>();

        if (text.length() <= maxChunkSizeChars || separatorIndex >= SEPARATORS.length) {
            if (StringUtils.hasText(text)) {
                chunks.add(text);
            }
            return chunks;
        }

        String separator = SEPARATORS[separatorIndex];
        String[] splits = text.split("\\Q" + separator + "\\E", -1);

        for (int i = 0; i < splits.length; i++) {
            String chunk = splits[i];

            if (i < splits.length - 1) {
                chunk = chunk + separator;
            }

            if (chunk.length() <= maxChunkSizeChars) {
                if (StringUtils.hasText(chunk)) {
                    chunks.add(chunk);
                }
            } else {
                List<String> subChunks = splitByLevel(chunk, separatorIndex + 1);
                chunks.addAll(subChunks);
            }
        }

        return chunks;
    }

    private List<String> mergeSmallChunks(List<String> chunks) {
        List<String> merged = new ArrayList<>();
        String current = "";

        for (String chunk : chunks) {
            String candidate = current.isEmpty() ? chunk : current + chunk;

            if (candidate.length() <= maxChunkSizeChars) {
                current = candidate;
            } else {
                if (StringUtils.hasText(current)) {
                    merged.add(current);
                }
                current = chunk;
            }
        }

        if (StringUtils.hasText(current)) {
            merged.add(current);
        }

        return merged;
    }

    private List<String> applyOverlap(List<String> chunks) {
        if (chunks.isEmpty() || overlapChars == 0) {
            return chunks;
        }

        List<String> overlapped = new ArrayList<>();
        overlapped.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1);
            String current = chunks.get(i);

            String overlapContext = extractSentenceOverlap(previous);

            String chunkWithOverlap = StringUtils.hasText(overlapContext)
                ? overlapContext + " " + current
                : current;

            overlapped.add(chunkWithOverlap);
        }

        return overlapped;
    }

    /**
     * Walks backward from the end of {@code previous} accumulating whole sentences until the
     * overlap budget is reached, so the overlap boundary always falls on a sentence break
     * instead of an arbitrary character offset. The last sentence is always included in full
     * even if it alone exceeds the budget, since a partial sentence loses more meaning than an
     * oversized one.
     */
    private String extractSentenceOverlap(String previous) {
        List<String> sentences = splitIntoSentences(previous);
        if (sentences.isEmpty()) {
            return "";
        }

        StringBuilder overlap = new StringBuilder();
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i);
            if (overlap.length() > 0 && sentence.length() + overlap.length() > overlapChars) {
                break;
            }
            overlap.insert(0, sentence);
        }

        return overlap.toString().trim();
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

    private String clean(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        String cleaned = text;

        cleaned = cleaned.replace("\r\n", "\n").replace('\r', '\n');

        cleaned = CONTROL_CHARACTERS.matcher(cleaned).replaceAll("");

        cleaned = unwrapLines(cleaned);

        cleaned = MULTIPLE_SPACES.matcher(cleaned).replaceAll(" ");

        cleaned = MULTIPLE_NEWLINES.matcher(cleaned).replaceAll("\n\n");

        cleaned = cleaned.strip();

        return cleaned;
    }

    /**
     * Rejoins lines that PDF text extraction broke purely for page-width word-wrap, while
     * preserving newlines that mark a real sentence, clause, or list-item boundary. A line is
     * merged into the next when it has no terminal punctuation and the next line isn't a list
     * marker; a trailing wrap-hyphen ("In-\npatient") is removed and the two lines joined
     * directly instead of with a space.
     */
    private String unwrapLines(String text) {
        String[] rawLines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        String pendingLine = null;

        for (String rawLine : rawLines) {
            String line = rawLine.strip();

            if (line.isEmpty()) {
                if (pendingLine != null) {
                    result.append(pendingLine).append('\n');
                    pendingLine = null;
                }
                result.append('\n');
                continue;
            }

            if (pendingLine == null) {
                pendingLine = line;
                continue;
            }

            if (ENDS_WITH_WRAP_HYPHEN.matcher(pendingLine).matches() && Character.isLowerCase(line.charAt(0))) {
                pendingLine = pendingLine.substring(0, pendingLine.length() - 1) + line;
            } else if (!ENDS_WITH_SENTENCE_TERMINATOR.matcher(pendingLine).matches()
                    && !LIST_MARKER_START.matcher(line).matches()) {
                pendingLine = pendingLine + " " + line;
            } else {
                result.append(pendingLine).append('\n');
                pendingLine = line;
            }
        }

        if (pendingLine != null) {
            result.append(pendingLine);
        }

        return result.toString();
    }

    private int estimateTokenCount(String text) {
        return (int) Math.ceil(text.length() / 4.0);
    }
}