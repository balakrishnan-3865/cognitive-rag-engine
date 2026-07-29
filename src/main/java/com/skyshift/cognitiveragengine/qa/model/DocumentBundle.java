package com.skyshift.cognitiveragengine.qa.model;

import org.springframework.ai.document.Document;

import java.util.List;

public record DocumentBundle(
        List<Document> documents
) {}
