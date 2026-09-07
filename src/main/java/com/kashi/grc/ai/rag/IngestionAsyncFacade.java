package com.kashi.grc.ai.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async entry point for ingestion.
 *
 * ── WHY A SEPARATE BEAN AND NOT AN @Async METHOD ON IngestionService ─────────
 * Same proxy trap as AiInteractionRecorder, one layer worse. An @Async method
 * calling a @Transactional method on the same bean bypasses the transaction
 * proxy, so the ingestion would run off the request thread but WITHOUT the
 * transaction it declares — chunk rows written outside any transactional
 * boundary, with no rollback if the vector upsert fails partway.
 *
 * Crossing a real bean boundary makes both annotations take effect. This is the
 * kind of bug that produces a half-written corpus and no error anywhere.
 *
 * Everything here is fire-and-forget. Ingestion must never sit on the critical
 * path of a policy save: it is a network call to a third party with its own
 * latency and its own outages, and a user pressing Save should not be able to
 * see any of that.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionAsyncFacade {

    private final IngestionService ingestionService;

    @Async("aiTaskExecutor")
    public void ingest(IngestionService.IngestRequest req) {
        try {
            ingestionService.ingest(req);
        } catch (Exception e) {
            log.error("[AI-INGEST] async ingestion failed for {}#{}: {}",
                    req.sourceType(), req.sourceId(), e.getMessage());
        }
    }

    @Async("aiTaskExecutor")
    public void reindexStale() {
        try {
            ingestionService.reindexStale();
        } catch (Exception e) {
            log.error("[AI-INGEST] reindex sweep failed: {}", e.getMessage());
        }
    }
}
