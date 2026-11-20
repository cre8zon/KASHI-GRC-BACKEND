package com.kashi.grc.ai.rag;

import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.domain.AiDocumentChunk;
import com.kashi.grc.ai.repository.AiDocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Keeps the vector store in step with MySQL, without anyone calling the
 * reindex endpoint by hand.
 *
 * ── WHY THIS EXISTS ────────────────────────────────────────────────────────
 * MySQL (ai_document_chunks) is the truth; Qdrant is a rebuildable index of
 * it. Two things left the index behind and nothing ever caught up:
 *
 *   1. Qdrant unreachable during ingestion. The chunk rows are saved with
 *      indexed_at = NULL, and only POST /v1/ai/admin/corpus/reindex would
 *      ever finish them.
 *   2. The collection lost or replaced (a free cluster suspended and deleted,
 *      a new cluster, a changed URL). MySQL still says every chunk is
 *      indexed, so even the manual reindex skipped them — retrieval ran
 *      against an empty index and returned nothing, silently.
 *
 * ── WHAT IT DOES ───────────────────────────────────────────────────────────
 * Three minutes after startup, then every hour after the previous run ends:
 *   - Qdrant unreachable → log and wait for the next run. Nothing changes.
 *   - Qdrant reachable but EMPTY while MySQL holds indexed chunks → the
 *     collection was lost; mark those chunks unindexed.
 *   - Re-embed and upsert every unindexed or stale-model chunk, on the
 *     aiTaskExecutor pool (never on the scheduler thread, which is shared
 *     with the WebSocket broker).
 *
 * When nothing is out of step this costs one Qdrant stats call and one
 * MySQL query — no embedding calls, no tokens.
 *
 * Switches: app.ai.vector-reconcile.initial-delay-ms (default 180000),
 *           app.ai.vector-reconcile.interval-ms      (default 3600000).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorIndexReconciler {

    private final AiProperties              props;
    private final VectorStore               vectorStore;
    private final AiDocumentChunkRepository chunkRepository;
    private final IngestionAsyncFacade      ingestionAsync;

    @Scheduled(initialDelayString = "${app.ai.vector-reconcile.initial-delay-ms:180000}",
            fixedDelayString   = "${app.ai.vector-reconcile.interval-ms:3600000}")
    public void reconcile() {
        if (!props.isEnabled()) return;

        try {
            Map<String, Object> stats = vectorStore.stats();
            if (!Boolean.TRUE.equals(stats.get("available"))) {
                log.warn("[AI-VECTOR-RECONCILE] Vector store unreachable — skipping this run | {}",
                        stats.get("error"));
                return;
            }

            long points = stats.get("pointsCount") instanceof Number n ? n.longValue() : -1L;
            if (points == 0) {
                List<AiDocumentChunk> believedIndexed = chunkRepository.findByQuarantinedFalseAndIndexedAtIsNotNull();
                if (!believedIndexed.isEmpty()) {
                    believedIndexed.forEach(c -> c.setIndexedAt(null));
                    chunkRepository.saveAll(believedIndexed);
                    log.warn("[AI-VECTOR-RECONCILE] Collection is empty but {} chunk(s) were marked indexed — "
                            + "collection lost or replaced; queued for re-indexing", believedIndexed.size());
                }
            }

            // Re-embeds only unindexed or stale-model chunks; a no-op when the
            // index is current. Async, so a long run never holds the scheduler.
            ingestionAsync.reindexStale();

        } catch (Exception e) {
            log.error("[AI-VECTOR-RECONCILE] Run failed — will retry next interval: {}", e.toString());
        }
    }
}