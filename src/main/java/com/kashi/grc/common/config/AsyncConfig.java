package com.kashi.grc.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables Spring's @Async support.
 *
 * Required by WorkflowEventListener — WebSocket pushes are fired @Async so they
 * don't block the main DB transaction thread. If this class is missing, @Async
 * methods run synchronously (no error, but defeats the purpose of @Async).
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    // No additional config needed — Spring uses a SimpleAsyncTaskExecutor by default.
    // For production, define a ThreadPoolTaskExecutor bean here to control
    // pool size, queue capacity, and thread naming.
}