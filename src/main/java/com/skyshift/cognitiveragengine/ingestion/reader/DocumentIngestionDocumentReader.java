package com.skyshift.cognitiveragengine.ingestion.reader;

import com.skyshift.cognitiveragengine.document.model.entity.DocumentEntity;
import com.skyshift.cognitiveragengine.ingestion.parser.DocumentParserFactory;
import com.skyshift.cognitiveragengine.ingestion.parser.DocumentParser;
import com.skyshift.cognitiveragengine.ingestion.parser.ParseException;
import com.skyshift.cognitiveragengine.storage.service.ObjectStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

import java.io.InputStream;
import java.util.List;

@Slf4j
public class DocumentIngestionDocumentReader implements DocumentReader {

    private final ObjectStorageService objectStorageService;
    private final DocumentParserFactory parserFactory;
    private final DocumentEntity documentEntity;

    public DocumentIngestionDocumentReader(
            ObjectStorageService objectStorageService,
            DocumentParserFactory parserFactory,
            DocumentEntity documentEntity) {
        this.objectStorageService = objectStorageService;
        this.parserFactory = parserFactory;
        this.documentEntity = documentEntity;
    }

    @Override
    public List<Document> get() {
        if (documentEntity == null) {
            throw new IllegalArgumentException("DocumentEntity cannot be null");
        }

        String bucket = documentEntity.getStorageBucket();
        String objectKey = documentEntity.getStorageObjectKey();
        String extension = documentEntity.getFileExtension();

        log.info("Reading document: bucket={}, key={}, ext={}", bucket, objectKey, extension);

        try {
            InputStream stream = objectStorageService.downloadObject(bucket, objectKey);

            DocumentParser parser = parserFactory.getParser(extension);

            List<Document> documents = parser.parse(stream);

            log.info("Parsed document: {} pages/sections", documents.size());
            return documents;

        } catch (Exception e) {
            log.error("Failed to read/parse document: {}/{}", bucket, objectKey, e);
            throw new RuntimeException("Document ingestion failed: " + e.getMessage(), e);
        }
    }
}