package com.skyshift.cognitiveragengine.retrieval.reranker.model;

import org.springframework.ai.document.Document;

public record ScoredDocument(Document document, double score) {}