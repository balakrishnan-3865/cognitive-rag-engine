package com.skyshift.cognitiveragengine.document.listener;

import com.skyshift.cognitiveragengine.document.event.DocumentUploadedEvent;
import com.skyshift.cognitiveragengine.ingestion.service.ParseAndChunkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 2: @Async ingestion listeners must run on the dedicated virtual-thread executor
 * (Section 12), not the platform-thread-backed default pool.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentIngestionAsyncListenerVirtualThreadTest {

    @Autowired
    private DocumentIngestionAsyncListener documentIngestionAsyncListener;

    @MockitoBean
    private ParseAndChunkService parseAndChunkService;

    @Test
    void onDocumentUploaded_runsOnVirtualThread() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean isVirtual = new AtomicBoolean(false);
        AtomicReference<String> threadName = new AtomicReference<>();

        doAnswer(invocation -> {
            Thread current = Thread.currentThread();
            isVirtual.set(current.isVirtual());
            threadName.set(current.getName());
            latch.countDown();
            return null;
        }).when(parseAndChunkService).parseAndChunkDocument(anyLong(), anyLong());

        documentIngestionAsyncListener.onDocumentUploaded(new DocumentUploadedEvent(1L, 2L));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "async method never ran");
        assertTrue(isVirtual.get(), "expected a virtual thread, ran on: " + threadName.get());
    }
}
