package com.skyshift.cognitiveragengine.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;

import java.util.concurrent.Executors;

/**
 * Configuration for asynchronous processing in the application.
 * Enables @Async annotation for non-blocking method execution.
 * Thread pool settings configured in application.yaml under spring.task.execution.
 */
@Configuration
@EnableAsync
public class AsyncConfiguration {

    /**
     * Dedicated virtual-thread-per-task executor for the ingestion pipeline listeners, so
     * long-running Docling calls don't pin the small platform-thread pool backing @Async by
     * default (spring.task.execution.pool, core 5 / max 10).
     */
    @Bean("ingestionVirtualExecutor")
    public TaskExecutor ingestionVirtualExecutor() {
        return new ConcurrentTaskExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
}