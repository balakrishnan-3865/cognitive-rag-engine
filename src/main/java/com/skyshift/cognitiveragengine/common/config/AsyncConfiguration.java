package com.skyshift.cognitiveragengine.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Configuration for asynchronous processing in the application.
 * Enables @Async annotation for non-blocking method execution.
 * Thread pool settings configured in application.yaml under spring.task.execution.
 */
@Configuration
@EnableAsync
public class AsyncConfiguration {
}