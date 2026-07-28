package com.skyshift.cognitiveragengine.ingestion.parser;

import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DocumentParserFactory {

    private final Map<String, DocumentParser> parserByExtension = new LinkedHashMap<>();

    // Spring auto-injects all DocumentParser beans
    public DocumentParserFactory(List<DocumentParser> parsers) {
        for (DocumentParser parser : parsers) {
            for (String ext : parser.getSupportedExtensions()) {
                parserByExtension.put(ext.toLowerCase(Locale.ROOT), parser);
            }
        }
    }

    public DocumentParser getParser(String extension) {
        String normalizedExtension = normalizeExtension(extension);
        DocumentParser parser = parserByExtension.get(normalizedExtension);
        if (parser == null) {
            throw new BusinessException("Unsupported document type: " + normalizedExtension);
        }
        return parser;
    }

    private String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            throw new BusinessException("Document extension cannot be empty");
        }
        /* extension.replaceFirst("^\\.", "") --> Removes the first dot (.) character if it appears at the very beginning of the string.
         * So it removes a leading dot like .pdf → pdf
         * */
        return extension.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT);
    }
}
