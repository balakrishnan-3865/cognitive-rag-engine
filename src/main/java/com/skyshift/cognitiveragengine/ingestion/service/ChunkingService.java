package com.skyshift.cognitiveragengine.ingestion.service;

import com.skyshift.cognitiveragengine.ingestion.model.dto.ChunkMetadata;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChunkingService {

    private final int chunkSizeTokens;
    private final int chunkOverlapPercentage;

    public ChunkingService(
            @Value("${document.ingestion.chunk-size-tokens:300}") int chunkSizeTokens,
            @Value("${document.ingestion.chunk-overlap-percentage:20}") int chunkOverlapPercentage) {
        this.chunkSizeTokens = chunkSizeTokens;
        this.chunkOverlapPercentage = chunkOverlapPercentage;
    }

    public List<DocumentChunkEntity> chunk(
            List<Document> documents,
            Long documentId,
            Long groupId) {

        int overlapTokens = (int) (chunkSizeTokens * chunkOverlapPercentage / 100.0);

        log.info("Chunking documents: size={} tokens, overlap={} tokens",
            chunkSizeTokens, overlapTokens);

        TokenTextSplitter splitter = new TokenTextSplitter(
            chunkSizeTokens,
            overlapTokens,
            0,
            8191,
            true);

        List<DocumentChunkEntity> allChunks = new ArrayList<>();
        int globalChunkIndex = 0;
        LocalDateTime now = LocalDateTime.now();

        for (Document doc : documents) {
            List<Document> splitDocs = splitter.apply(List.of(doc));
            int pageNumber = Integer.parseInt(
                doc.getMetadata().getOrDefault("page_number", "1").toString());

            for (Document chunk : splitDocs) {
                ChunkMetadata metadata = ChunkMetadata.builder()
                    .pageNumber(pageNumber)
                    .tokenCount(estimateTokenCount(chunk.getText()))
                    .source(doc.getMetadata().getOrDefault("source", "unknown").toString())
                    .chunkStrategy("fixed-token-300-v1")
                    .chunkIndex(globalChunkIndex)
                    .documentId(documentId)
                    .groupId(groupId)
                    .build();

                DocumentChunkEntity chunkEntity = DocumentChunkEntity.builder()
                        .documentId(documentId)
                        .groupId(groupId)
                        .chunkNumber(globalChunkIndex)
                        .chunkText(chunk.getText())
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

    private int estimateTokenCount(String text) {
        return (int) Math.ceil(text.length() / 4.0);
    }
}