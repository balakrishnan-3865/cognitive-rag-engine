package com.skyshift.cognitiveragengine.retrieval.elasticsearch.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.model.KeywordHit;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.model.SparseChunkDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ElasticsearchChunkIndexService {

    private static final int BATCH_SIZE = 50;
    private final String CHUNK_INDEX_NAME = "rag_sparse_chunks";
    private volatile boolean indexInitialized = false;
    private static final double KEYWORD_SCORE_REFERENCE = 100D;
    private static final String READY_STATUS = "READY";

    private final ElasticsearchClient elasticsearchClient;

    public ElasticsearchChunkIndexService(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    public void ensureIndexExists() {
        if (indexInitialized) {
            return;
        }

        synchronized (this) {
            if (indexInitialized) {
                return;
            }

            provisionIndex();
            indexInitialized = true;
        }
    }

    private void provisionIndex() {
        try {
            // Check and provision index
            boolean indexExists = elasticsearchClient.indices()
                    .exists(req -> req.index(CHUNK_INDEX_NAME))
                    .value();

            if (!indexExists) {
                createIndex();
                log.info("Elasticsearch index '{}' created successfully", CHUNK_INDEX_NAME);
            } else {
                log.debug("Elasticsearch index '{}' already exists", CHUNK_INDEX_NAME);
            }
        } catch (IOException e) {
            log.error("Failed to check or create Elasticsearch index '{}'", CHUNK_INDEX_NAME, e);
            throw new BusinessException("Failed to initialize Elasticsearch index", e);
        }
    }

    private void createIndex() throws IOException {
        elasticsearchClient.indices().create(req -> req
                .index(CHUNK_INDEX_NAME)
                .settings(settings -> settings
                        .numberOfShards("1")
                        .numberOfReplicas("0")
                        .refreshInterval(ri -> ri.time("-1"))
                )
                .mappings(mappings -> mappings
                        .properties("chunkId", prop -> prop.long_(l -> l))
                        .properties("groupId", prop -> prop.long_(l -> l))
                        .properties("documentId", prop -> prop.long_(l -> l))
                        .properties("chunkIndex", prop -> prop.integer(i -> i))
                        .properties("fileName", prop -> prop
                                .text(t -> t
                                        .fields("keyword", f -> f.keyword(k -> k.ignoreAbove(256)))
                                )
                        )
                        // 1. Raw text field for BM25 keyword matching
                        .properties("chunkText", prop -> prop.text(t -> t))
                        .properties("status", prop -> prop
                                .keyword(k -> k.index(true))
                        )
                        .properties("deleted", prop -> prop.boolean_(b -> b))
                )
        );
    }

    public void indexChunks(String fileName, List<DocumentChunkEntity> chunks) throws IOException {
        ensureIndexExists();

        if (chunks == null || chunks.isEmpty()) {
            log.warn("No chunks provided for indexing with fileName: {}", fileName);
            return;
        }

        List<String> allIndexedIds = new ArrayList<>();

        try {
            // Process chunks in batches
            for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, chunks.size());
                List<DocumentChunkEntity> batch = chunks.subList(i, end);
                List<String> batchIndexedIds = new ArrayList<>();

                processBulkIndexBatch(fileName, batch, batchIndexedIds);
                allIndexedIds.addAll(batchIndexedIds);
                log.debug("Successfully indexed batch of {} chunks for file: {}", batch.size(), fileName);
            }

            elasticsearchClient.indices().refresh(req -> req.index(CHUNK_INDEX_NAME));
            log.info("Successfully indexed all {} chunks for file: {}", chunks.size(), fileName);
        } catch (Exception e) {
            log.error("Overall indexing failed for file: {}. Rolling back all indexed chunks...", fileName, e);
            rollbackIndexedChunks(allIndexedIds);
            throw e;
        }
    }

    private void processBulkIndexBatch(String fileName, List<DocumentChunkEntity> batch, List<String> batchIndexedIds) throws IOException {
        List<BulkOperation> operations = new ArrayList<>();

        for (DocumentChunkEntity chunk : batch) {
            validateChunk(chunk);
            SparseChunkDto sparseChunk = convertToSparseChunkDto(chunk, fileName);
            operations.add(BulkOperation.of(op -> op
                    .index(idx -> idx
                            .index(CHUNK_INDEX_NAME)
                            .id(chunk.getId().toString())
                            .document(sparseChunk)
                    )
            ));
            batchIndexedIds.add(chunk.getId().toString());
        }

        BulkResponse bulkResponse = elasticsearchClient.bulk(req -> req.operations(operations));

        if (bulkResponse.errors()) {
            log.error("Errors occurred during bulk indexing for file: {}. Rolling back...", fileName);
            throw new RuntimeException("Bulk indexing failed with errors");
        }
    }

    public List<KeywordHit> searchChunks(String query, Long groupId, int topK) throws IOException {
        ensureIndexExists();

        if (query == null || query.trim().isEmpty() || groupId == null || groupId <= 0L || topK <= 0) {
            return new ArrayList<>();
        }

        try {
            SearchResponse<SparseChunkDto> searchResponse = buildElserSearchRequest(groupId, topK, query);

            List<KeywordHit> results = searchResponse.hits().hits().stream()
                    .map(hit -> {
                        SparseChunkDto source = hit.source();
                        double rawScore = hit.score();
                        return new KeywordHit(
                                source.documentId(),
                                source.chunkId(),
                                source.chunkIndex(),
                                source.fileName(),
                                source.chunkText(),
                                rawScore,
                                normalizeKeywordScore(rawScore)
                        );
                    })
                    .collect(Collectors.toList());

            log.debug("Found {} results for query: {} with groupId: {}", results.size(), query, groupId);
            return results;
        } catch (IOException e) {
            log.error("Failed to search chunks with query: {}", query, e);
            throw new RuntimeException("Search operation failed: " + e.getMessage(), e);
        }
    }

    private SearchResponse<SparseChunkDto> buildElserSearchRequest(Long groupId, int topK, String query) throws IOException {
        return elasticsearchClient.search(req -> req
                        .index(CHUNK_INDEX_NAME)
                        .query(q -> q
                                .bool(b -> b
                                        .must(m -> m
                                                .term(t -> t
                                                        .field("groupId")
                                                        .value(v -> v.longValue(groupId))
                                                )
                                        )
                                        .must(m -> m
                                                .multiMatch(mm -> mm
                                                        .query(query)
                                                        .fields("chunkText", "fileName")
                                                )
                                        )
                                )
                        )
                        .size(topK),
                SparseChunkDto.class
        );
    }

    public void deleteChunksByDocumentId(Long documentId) throws IOException {
        ensureIndexExists();

        if (documentId == null) {
            log.warn("No documentId provided for deletion");
            return;
        }

        try {
            elasticsearchClient.deleteByQuery(req -> req
                    .index(CHUNK_INDEX_NAME)
                    .query(q -> q
                            .term(t -> t
                                    .field("documentId")
                                    .value(v -> v.longValue(documentId))
                            )
                    )
            );

            log.info("Successfully deleted all chunks for documentId: {}", documentId);
        } catch (IOException e) {
            log.error("IOException during deletion of chunks for documentId: {}", documentId, e);
            throw new RuntimeException("Failed to delete chunks by documentId: " + e.getMessage(), e);
        }
    }

    public void deleteChunksByIds(String indexName, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("No IDs provided for deletion");
            return;
        }

        try {
            List<BulkOperation> operations = new ArrayList<>();

            for (String id : ids) {
                operations.add(BulkOperation.of(op -> op
                        .delete(del -> del
                                .index(indexName)
                                .id(id)
                        )
                ));
            }

            BulkResponse bulkResponse = elasticsearchClient.bulk(req -> req.operations(operations));

            if (bulkResponse.errors()) {
                log.error("Errors occurred during bulk deletion from index: {}. Some chunks may not have been deleted.", indexName);
                throw new RuntimeException("Bulk deletion failed with errors");
            }

            log.info("Successfully deleted {} chunks from index: {}", ids.size(), indexName);
        } catch (IOException e) {
            log.error("IOException during chunk deletion from index: {}", indexName, e);
            throw new RuntimeException("Failed to delete chunks: " + e.getMessage(), e);
        }
    }

    private void rollbackIndexedChunks(List<String> indexedIds) {
        if (indexedIds == null || indexedIds.isEmpty()) {
            return;
        }

        try {
            deleteChunksByIds(CHUNK_INDEX_NAME, indexedIds);
            log.info("Rollback successful: deleted {} indexed chunks", indexedIds.size());
        } catch (Exception e) {
            log.error("Rollback failed: unable to delete indexed chunks. Manual cleanup may be required.", e);
        }
    }

    private void validateChunk(DocumentChunkEntity chunk) {
        if (chunk == null
                || chunk.getId() == null
                || chunk.getGroupId() == null
                || chunk.getDocumentId() == null
                || chunk.getChunkNumber() == null
                || !StringUtils.hasText(chunk.getChunkText())) {
            throw new BusinessException("ES indexing missing required chunk fields");
        }
    }

    private SparseChunkDto convertToSparseChunkDto(DocumentChunkEntity chunk, String fileName) {
        return new SparseChunkDto(
                chunk.getId(),
                chunk.getGroupId(),
                chunk.getDocumentId(),
                chunk.getChunkNumber(),
                fileName,
                chunk.getChunkText(),
                READY_STATUS,
                false
        );
    }

    private double normalizeKeywordScore(double rawScore) {
        if (rawScore <= 0D) {
            return 0D;
        }
        return Math.min(1D, Math.log1p(rawScore) / Math.log1p(KEYWORD_SCORE_REFERENCE));
    }
}