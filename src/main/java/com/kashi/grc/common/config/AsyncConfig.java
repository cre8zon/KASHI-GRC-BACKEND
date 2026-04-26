package com.kashi.grc.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async configuration — replaces Spring's default SimpleAsyncTaskExecutor.
 *
 * SimpleAsyncTaskExecutor creates a NEW THREAD for every @Async call — no pooling.
 * Under load (multiple onboards, invitations, notifications firing simultaneously)
 * this creates thread explosion and contention.
 *
 * ThreadPoolTaskExecutor maintains a warm pool: tasks queue when busy rather than
 * spawning unlimited threads. Named "taskExecutor" to resolve the ambiguity warning:
 * "More than one TaskExecutor bean found... none is named 'taskExecutor'"
 * seen in startup logs alongside the WebSocket executor beans.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Core pool — always-warm threads for email, notifications, WebSocket pushes
        executor.setCorePoolSize(4);

        // Max pool — burst capacity for spikes (multiple onboards at once)
        executor.setMaxPoolSize(10);

        // Queue tasks when all threads busy rather than spawning past max
        executor.setQueueCapacity(50);

        // Thread name prefix — visible in logs and thread dumps for debugging
        executor.setThreadNamePrefix("kashi-async-");

        // Graceful shutdown — wait up to 10s for in-flight async tasks to finish
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);

        executor.initialize();
        return executor;
    }
}