package com.skyshift.cognitiveragengine.ingestion.parser;

import org.springframework.ai.document.Document;

import java.io.InputStream;
import java.util.List;

public interface DocumentParser {

    List<String> getSupportedExtensions();

    List<Document> parse(InputStream stream) throws ParseException;
}