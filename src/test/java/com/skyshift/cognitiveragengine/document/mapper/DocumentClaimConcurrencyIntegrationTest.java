package com.skyshift.cognitiveragengine.document.mapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 2 concurrency guard: two concurrent callers racing to claim the same document for
 * ingestion must not both win (Section 19). Not @Transactional — each thread needs its own
 * connection/transaction to actually race, so cleanup is manual.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentClaimConcurrencyIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocumentMapper documentMapper;

    private Long documentId;

    @AfterEach
    void cleanup() {
        if (documentId != null) {
            jdbcTemplate.update("delete from documents where id = ?", documentId);
        }
    }

    @Test
    void claimForProcessing_concurrentCallers_onlyOneSucceeds() throws Exception {
        documentId = createDocument("claim-concurrency-test.pdf", "PENDING");

        int threadCount = 2;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return documentMapper.claimForProcessing(
                        documentId, List.of("PENDING", "FAILED"), "PROCESSING");
                }));
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            int totalClaimed = 0;
            for (Future<Integer> future : futures) {
                totalClaimed += future.get(5, TimeUnit.SECONDS);
            }

            assertEquals(1, totalClaimed, "Exactly one concurrent caller should win the claim");

            String status = jdbcTemplate.queryForObject(
                "select status from documents where id = ?", String.class, documentId);
            assertEquals("PROCESSING", status);
        } finally {
            executor.shutdown();
        }
    }

    private Long createDocument(String objectKey, String status) {
        jdbcTemplate.update(
            "insert into documents (group_id, uploaded_user_id, file_name, file_extension, file_size, storage_bucket, storage_object_key, status) " +
                "values (1, 1, 'claim-test.pdf', 'pdf', 100, 'bucket', ?, ?)",
            objectKey, status);
        return jdbcTemplate.queryForObject(
            "select id from documents where storage_object_key = ?", Long.class, objectKey);
    }
}
