package com.skyshift.cognitiveragengine.retrieval.elasticsearch.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.skyshift.cognitiveragengine.common.exception.BusinessException;
import com.skyshift.cognitiveragengine.ingestion.exception.NoChunksFoundException;
import com.skyshift.cognitiveragengine.ingestion.model.entity.DocumentChunkEntity;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.model.KeywordHit;
import com.skyshift.cognitiveragengine.retrieval.elasticsearch.model.SparseChunkDto;
import io.github.resilience4j.retry.annotation.Retry;
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

    public void indexChunks(Long documentId, String fileName, List<DocumentChunkEntity> chunks) throws IOException {
        ensureIndexExists();

        if (chunks == null || chunks.isEmpty()) {
            throw new NoChunksFoundException("Cannot index chunks: no chunks provided for fileName=" + fileName);
        }

        int totalBatches = (chunks.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        log.info("Starting Elasticsearch indexing: documentId={}, fileName={}, totalChunks={}, totalBatches={}",
                 documentId, fileName, chunks.size(), totalBatches);

        try {
            // Process chunks in batches
            for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, chunks.size());
                List<DocumentChunkEntity> batch = chunks.subList(i, end);
                int batchNum = (i / BATCH_SIZE) + 1;
                processBulkIndexBatch(fileName, batch, batchNum, totalBatches);
            }

            elasticsearchClient.indices().refresh(req -> req.index(CHUNK_INDEX_NAME));
            log.info("Elasticsearch indexing completed successfully: documentId={}, fileName={}, indexedChunks={}",
                     documentId, fileName, chunks.size());

        } catch (Exception e) {
            log.error("Elasticsearch indexing failed: documentId={}, fileName={}, totalChunks={}, failureMessage={}. " +
                     "Initiating compensating rollback...",
                     documentId, fileName, chunks.size(), e.getMessage());

            try {
                deleteChunksByDocumentId(documentId);
                log.info("Compensating rollback completed: documentId={}, fileName={}, rolledBackChunks={}",
                         documentId, fileName, chunks.size());
            } catch (IOException rollbackError) {
                log.error("CRITICAL: Elasticsearch rollback FAILED: documentId={}, fileName={}. " +
                         "Manual cleanup required. Orphaned documents may exist in Elasticsearch for this documentId.",
                         documentId, fileName, rollbackError);
                throw new IOException("Rollback failed after indexing failure for file: " + fileName, rollbackError);
            }

            throw e;
        }
    }


    /**
     * Indexes a single batch of chunks with per-batch retry and exponential backoff.
     * Elasticsearch is external (not transactional), so @Retry handles transient failures.
     * If batch fails after max retries, fallback throws IOException to trigger compensating rollback.
     *
     * Retry: 5 max attempts with exponential backoff (500ms initial, 2x multiplier)
     * Configuration: application.yaml resilience4j.retry.instances.elasticsearch-batch
     * Retries on: IOException, ResponseException (network/temporary errors)
     */
    @Retry(name = "elasticsearch-batch", fallbackMethod = "handleBulkIndexBatchFailure")
    public void processBulkIndexBatch(String fileName, List<DocumentChunkEntity> batch, int batchNum, int totalBatches) throws IOException {
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
        }

        try {
            BulkResponse bulkResponse = elasticsearchClient.bulk(req -> req.operations(operations));

            if (bulkResponse.errors()) {
                log.warn("Bulk indexing encountered errors for batch {}/{} in file: {}. Will retry...",
                         batchNum, totalBatches, fileName);
                throw new IOException("Bulk indexing failed with errors");
            }

        } catch (IOException e) {
            log.warn("Bulk indexing failed for batch {}/{} in file: {} (will retry with exponential backoff)",
                     batchNum, totalBatches, fileName);
            throw e;
        }
    }

    /**
     * Fallback: Called when @Retry exhausts max attempts for a batch.
     * Throws IOException to trigger compensating rollback at indexChunks level.
     *
     * Method signature must match processBulkIndexBatch() plus Throwable parameter.
     */
    private void handleBulkIndexBatchFailure(String fileName, List<DocumentChunkEntity> batch, int batchNum, int totalBatches, Throwable t) throws IOException {
        log.error("Bulk indexing PERMANENTLY FAILED after max retry attempts: batch {}/{} in file {} with {} chunks. " +
                 "Batch will be rolled back at document level.",
                 batchNum, totalBatches, fileName, batch.size());

        throw new IOException(
            "Bulk indexing failed after max retry attempts for batch " + batchNum + "/" + totalBatches +
            " in file: " + fileName + ". Error: " + t.getMessage(), t);
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

            log.info("Elasticsearch cleanup completed: documentId={}", documentId);
        } catch (IOException e) {
            log.error("Elasticsearch cleanup failed: documentId={}, error={}", documentId, e.getMessage(), e);
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