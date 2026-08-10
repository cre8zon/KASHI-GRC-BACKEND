package com.kashi.grc.common.perf;

import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory latency + query-count statistics, aggregated per endpoint.
 *
 * Keyed by the ROUTE TEMPLATE ("GET /v1/assessments/{assessmentId}"), not the raw
 * URI — otherwise every assessment id becomes its own bucket and nothing
 * aggregates.
 *
 * Deliberately in-memory and unbounded-by-time: this is a development profiler
 * you switch on, click through the app, and then read. It is not a metrics
 * backend. Data is lost on restart, which is fine — reset() before each run.
 *
 * Thread safety: one Stat per endpoint, guarded by synchronising on the Stat. The
 * contention is per-endpoint and the critical section is a few field updates, so
 * it will not distort the measurements it is taking.
 */
@Component
public class PerfRegistry {

    /** Keep a bounded sample of durations per endpoint so p95 is meaningful without unbounded memory. */
    private static final int MAX_SAMPLES = 500;

    private final Map<String, Stat> stats = new ConcurrentHashMap<>();

    public void record(String key, long millis, int queries) {
        Stat s = stats.computeIfAbsent(key, k -> new Stat());
        synchronized (s) {
            s.calls++;
            s.totalMs += millis;
            s.totalQueries += queries;
            if (millis > s.maxMs)        s.maxMs = millis;
            if (queries > s.maxQueries)  s.maxQueries = queries;
            if (s.samples.size() < MAX_SAMPLES) s.samples.add(millis);
        }
    }

    public void reset() {
        stats.clear();
    }

    /** Snapshot sorted by worst p95 first — the order you want to fix things in. */
    public List<EndpointStats> report() {
        List<EndpointStats> out = new ArrayList<>();
        stats.forEach((key, s) -> {
            synchronized (s) {
                List<Long> sorted = new ArrayList<>(s.samples);
                sorted.sort(Comparator.naturalOrder());
                long p95 = sorted.isEmpty() ? 0
                        : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1));
                out.add(EndpointStats.builder()
                        .endpoint(key)
                        .calls(s.calls)
                        .avgMs(s.calls == 0 ? 0 : s.totalMs / s.calls)
                        .p95Ms(p95)
                        .maxMs(s.maxMs)
                        .avgQueries(s.calls == 0 ? 0 : s.totalQueries / s.calls)
                        .maxQueries(s.maxQueries)
                        .totalMs(s.totalMs)
                        .build());
            }
        });
        out.sort(Comparator.comparingLong(EndpointStats::getP95Ms).reversed());
        return out;
    }

    private static final class Stat {
        long calls;
        long totalMs;
        long maxMs;
        long totalQueries;
        int  maxQueries;
        final List<Long> samples = new ArrayList<>();
    }

    @Data
    @Builder
    public static class EndpointStats {
        private String endpoint;
        private long   calls;
        private long   avgMs;
        private long   p95Ms;
        private long   maxMs;
        /** Average SQL statements per call — anything in the hundreds is an N+1. */
        private long   avgQueries;
        private int    maxQueries;
        /** Total wall time spent in this endpoint — surfaces cheap-but-chatty routes. */
        private long   totalMs;
    }
}