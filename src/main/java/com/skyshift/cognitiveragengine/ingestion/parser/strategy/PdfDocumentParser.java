package com.skyshift.cognitiveragengine.ingestion.parser.strategy;

import com.skyshift.cognitiveragengine.ingestion.parser.DocumentParser;
import com.skyshift.cognitiveragengine.ingestion.transformer.HeaderFooterCleanupTransformer;
import com.skyshift.cognitiveragengine.common.exception.ParseException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class PdfDocumentParser implements DocumentParser {

    private final HeaderFooterCleanupTransformer headerFooterCleanupTransformer;

    public PdfDocumentParser(HeaderFooterCleanupTransformer headerFooterCleanupTransformer) {
        this.headerFooterCleanupTransformer = headerFooterCleanupTransformer;
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of("pdf");
    }

    @Override
    public List<Document> parse(InputStream stream) throws ParseException {
        // Validate input
        if (stream == null) {
            throw new ParseException("Input stream cannot be null");
        }

        Path tempFile;
        try {
            tempFile = Files.createTempFile("pdf-ingest-", ".pdf");
        } catch (IOException e) {
            log.error("Failed to create temp file for PDF parsing", e);
            throw new ParseException("Failed to create temp file for PDF parsing: " + e.getMessage(), e);
        }

        // Stream to disk first so PDFBox parses via random-access file instead of buffering the whole PDF on heap
        try (InputStream source = stream) {
            Files.copy(source, tempFile, StandardCopyOption.REPLACE_EXISTING);

            try (PDDocument document = Loader.loadPDF(tempFile.toFile())) {

                List<Document> documents = new ArrayList<>();
                PDFTextStripper stripper = new PDFTextStripper();

                // Configure stripper for better text extraction
                stripper.setAddMoreFormatting(false);
                stripper.setSortByPosition(true);

                int numberOfPages = document.getNumberOfPages();
                log.debug("Starting PDF parsing with {} pages", numberOfPages);

                for (int pageNum = 1; pageNum <= numberOfPages; pageNum++) {
                    stripper.setStartPage(pageNum);
                    stripper.setEndPage(pageNum);

                    String pageText = stripper.getText(document);

                    if (pageText != null) {
                        String trimmedText = pageText.trim();
                        if (!trimmedText.isEmpty()) {
                            Document doc = new Document(trimmedText);
                            doc.getMetadata().put("page_number", String.valueOf(pageNum));
                            doc.getMetadata().put("total_pages", String.valueOf(numberOfPages));
                            doc.getMetadata().put("source", "pdf");
                            documents.add(doc);
                        } else {
                            log.debug("Page {} is empty or contains only whitespace", pageNum);
                        }
                    } else {
                        log.debug("Page {} returned null text", pageNum);
                    }
                }

                log.info("Successfully parsed PDF: {} pages, {} non-empty pages extracted",
                        numberOfPages, documents.size());
                return headerFooterCleanupTransformer.apply(documents);
            }
        } catch (IOException e) {
            log.error("IO error while parsing PDF", e);
            throw new ParseException("Failed to parse PDF: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error parsing PDF", e);
            throw new ParseException("Unexpected error parsing PDF: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("Failed to delete temp PDF file: {}", tempFile, e);
            }
        }
    }
}