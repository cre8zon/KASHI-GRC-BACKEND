package com.kashi.grc.uiconfig.scheduler;

import com.kashi.grc.common.cache.CacheNames;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.uiconfig.domain.MetricSnapshot;
import com.kashi.grc.uiconfig.repository.MetricSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

/**
 * Records every scalar dashboard metric, once a day, per tenant.
 *
 * ── WHY THIS EXISTS BEFORE THE CHARTS DO ──────────────────────────────────
 * Every dashboard chart is a snapshot of now. None shows direction, which is
 * the thing an executive actually reads. Trend charts are a small job — the
 * renderer already supports LINE_CHART and AREA_CHART — but they are useless
 * without history, and history cannot be reconstructed: the live tables hold
 * what is true today and nothing anywhere remembers March.
 *
 * So the capture runs from now, quietly, while the rest of the roadmap is
 * built. Turning the charts on later then shows months of real data instead of
 * a blank page and a three-month wait.
 *
 * ── WHAT IT CAPTURES ──────────────────────────────────────────────────────
 * Every NUMBER in each /stats payload, keyed '<module>.<field>'. Distributions
 * (byStatus, bySeverity) are skipped: a trend needs one line per day, and a
 * map of six categories is six series. Those are worth adding later with an
 * explicit key per category — deliberately not guessed at now.
 *
 * ── WHY IT CANNOT PILE UP ─────────────────────────────────────────────────
 * The unique key is (tenant, metric, day) and the job upserts, so a re-run —
 * retry, manual trigger, two instances — replaces rather than appends. Running
 * it five times in one day produces the same rows as running it once. That is
 * the same property that made the training recurrence sweep safe to schedule
 * while the Issues SLA escalation (GAPS item 4) still is not.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricSnapshotScheduler {

    private final MetricSnapshotRepository snapshotRepository;
    private final TenantRepository         tenantRepository;
    private final CacheManager             cacheManager;

    /**
     * The endpoints to sample, and the prefix their metrics get.
     *
     * Deliberately a list of suppliers rather than HTTP calls to our own API:
     * the scheduler has no session, and every /stats endpoint reads the tenant
     * from the logged-in context. Calling the services directly is also an
     * order of magnitude cheaper than a loopback request per tenant.
     */
    private final List<MetricSource> sources = new ArrayList<>();

    /** A named stats supplier. Registered by the modules that own them. */
    public record MetricSource(String prefix, Function<Long, Map<String, Object>> supplier) {}

    public void register(String prefix, Function<Long, Map<String, Object>> supplier) {
        sources.add(new MetricSource(prefix, supplier));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // THE JOB
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * 01:15 daily — before the training recurrence sweep at 02:30, so the
     * numbers recorded are yesterday's settled state rather than a picture
     * taken halfway through another job's writes.
     */
    @Scheduled(cron = "0 15 1 * * *")
    public void captureDaily() {
        log.info("[METRICS] Starting daily capture");
        try {
            int rows = captureAll(LocalDate.now());
            log.info("[METRICS] Complete | {} metric(s) recorded", rows);
        } catch (Exception e) {
            log.error("[METRICS] Capture failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Captures every registered source for every active tenant.
     *
     * One tenant's bad data must not stop the rest, so each is wrapped
     * individually — a job that aborts on the first failure records nothing for
     * everybody, which is the worst possible outcome for a history table.
     */
    @Transactional
    public int captureAll(LocalDate on) {
        if (sources.isEmpty()) {
            log.warn("[METRICS] No metric sources registered — nothing to capture. "
                    + "Modules register themselves via MetricSnapshotScheduler.register().");
            return 0;
        }

        // Clearing the stats cache first: a two-minute-old cached payload would
        // otherwise be recorded as today's value, which is harmless most days
        // and wrong on the day something changes at midnight.
        Optional.ofNullable(cacheManager.getCache(CacheNames.DASHBOARD_STATS))
                .ifPresent(c -> c.clear());

        int total = 0;
        for (Tenant t : tenantRepository.findAll()) {
            for (MetricSource src : sources) {
                try {
                    Map<String, Object> stats = src.supplier().apply(t.getId());
                    if (stats == null) continue;
                    total += record(t.getId(), src.prefix(), stats, on);
                } catch (Exception e) {
                    log.warn("[METRICS] {} failed for tenant {} — skipped | {}",
                            src.prefix(), t.getId(), e.getMessage());
                }
            }
        }
        return total;
    }

    /**
     * Writes the scalar entries of one payload.
     *
     * Only numbers. A distribution like byStatus is a map of six categories,
     * which is six series rather than one line, and guessing a key scheme for
     * them now would bake in something the trend widget then has to live with.
     * Booleans are skipped too: a true/false has no trend worth plotting.
     */
    private int record(Long tenantId, String prefix, Map<String, Object> stats, LocalDate on) {
        int n = 0;
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof Number num)) continue;

            String key = prefix + "." + e.getKey();
            BigDecimal value = new BigDecimal(num.toString());

            // Upsert, so a re-run replaces rather than appends.
            MetricSnapshot row = snapshotRepository
                    .findByTenantIdAndMetricKeyAndCapturedOn(tenantId, key, on)
                    .orElseGet(() -> MetricSnapshot.builder()
                            .tenantId(tenantId)
                            .metricKey(key)
                            .capturedOn(on)
                            .createdAt(LocalDateTime.now())
                            .build());
            row.setValue(value);
            snapshotRepository.save(row);
            n++;
        }
        return n;
    }
}