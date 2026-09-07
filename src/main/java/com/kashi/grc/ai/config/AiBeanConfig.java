package com.kashi.grc.ai.config;

import com.kashi.grc.ai.provider.EmbeddingProvider;
import com.kashi.grc.ai.rag.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Beans the AI module needs beyond component scanning.
 *
 * ── WHY A SEPARATE EXECUTOR ──────────────────────────────────────────────────
 * AsyncConfig's "taskExecutor" is a 4/10/50 pool serving email, notifications
 * and WebSocket pushes — short tasks that must stay responsive. Embedding a
 * 200-page vendor report is neither short nor responsive: it is minutes of
 * blocking network I/O. Sharing the pool would let one bulk ingestion starve
 * every notification in the system.
 *
 * Sizing reflects that these threads are almost always blocked on a socket, not
 * burning CPU, so the pool can be small and the queue deep. The
 * CallerRunsPolicy on saturation is deliberate: it applies backpressure to the
 * submitter rather than silently discarding an ingestion, and a dropped
 * ingestion is invisible until retrieval quietly gets worse.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AiBeanConfig {

    @Bean(name = "aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("kashi-ai-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Touch Qdrant once at startup so a misconfiguration is a boot-time log line
     * rather than a mystery on the first customer click.
     *
     * Never throws. Qdrant being unreachable must not stop the application —
     * retrieval degrades and everything else works, which is the same posture
     * RedisCircuitBreaker already takes for Redis.
     */
    @Bean
    public ApplicationRunner aiVectorStoreInitialiser(AiProperties props,
                                                      VectorStore vectorStore,
                                                      EmbeddingProvider embeddingProvider) {
        return args -> {
            if (!props.isEnabled()) { log.info("[AI-INIT] app.ai.enabled=false — AI module idle"); return; }
            try {
                vectorStore.ensureCollection(embeddingProvider.dimensions());
                log.info("[AI-INIT] vector store ready | {}", vectorStore.stats());
            } catch (Exception e) {
                log.warn("[AI-INIT] vector store unavailable at startup — retrieval will degrade: {}", e.getMessage());
            }
        };
    }
}
