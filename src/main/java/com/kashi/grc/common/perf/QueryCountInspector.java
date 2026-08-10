package com.kashi.grc.common.perf;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Counts every SQL statement Hibernate issues on the current thread.
 *
 * WHY THIS EXISTS:
 *   Wall-clock latency alone does not tell you WHY an endpoint is slow. A request
 *   that takes 11s because it issued 285 queries needs a completely different fix
 *   (batch the reads) from one that takes 11s on a single query (add an index).
 *   Query count per request is the number that distinguishes them, and it is the
 *   single most useful signal for finding N+1 problems.
 *
 * HOW IT WORKS:
 *   Hibernate calls inspect() for every statement it prepares. RequestPerfFilter
 *   resets the counter at the start of each HTTP request and reads it at the end,
 *   so the count is per-request. Tomcat reuses threads, hence the explicit reset
 *   rather than relying on initialValue.
 *
 * COST:
 *   One ThreadLocal get + int increment per statement. Negligible — but the whole
 *   thing is behind kashi.perf.enabled so it can be switched off in production.
 *
 * NOTE: this counts statements Hibernate prepares. Native JDBC issued outside
 * Hibernate (if any) will not appear.
 */
public final class QueryCountInspector implements StatementInspector {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    /** Longest statement seen on this thread since reset — helps identify the culprit. */
    private static final ThreadLocal<String> LAST = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        COUNT.get()[0]++;
        LAST.set(sql);
        return sql;   // never rewrite the statement
    }

    public static void reset() {
        COUNT.get()[0] = 0;
        LAST.remove();
    }

    public static int count() {
        return COUNT.get()[0];
    }

    public static String lastStatement() {
        return LAST.get();
    }

    /** Called by the filter's finally block — Tomcat reuses threads. */
    public static void clear() {
        COUNT.remove();
        LAST.remove();
    }
}