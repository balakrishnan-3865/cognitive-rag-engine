package com.skyshift.cognitiveragengine.ingestion.parser;

import com.skyshift.cognitiveragengine.common.exception.ParseException;
import org.springframework.ai.document.Document;

import java.io.InputStream;
import java.util.List;

public interface DocumentParser {

    List<String> getSupportedExtensions();

    List<Document> parse(InputStream stream) throws ParseException;
}