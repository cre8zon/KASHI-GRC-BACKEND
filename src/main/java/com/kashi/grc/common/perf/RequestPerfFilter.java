package com.kashi.grc.common.perf;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Times every HTTP request and records it against the matched route template,
 * together with the number of SQL statements the request issued.
 *
 * Switched on with:   kashi.perf.enabled=true
 * Read the results at: GET /v1/admin/perf/report
 *
 * ORDERING:
 *   LOWEST_PRECEDENCE so it sits closest to the controller. Timing here measures
 *   what the application actually does, without the auth filter chain in the
 *   number — that keeps the figures comparable to what you would fix in code.
 *
 * ROUTE TEMPLATE:
 *   BEST_MATCHING_PATTERN_ATTRIBUTE gives "/v1/assessments/{assessmentId}" rather
 *   than "/v1/assessments/71", so all calls to an endpoint aggregate into one row.
 *   It is only populated after the handler mapping runs, hence reading it AFTER
 *   the chain completes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = "kashi.perf.enabled", havingValue = "true")
public class RequestPerfFilter extends OncePerRequestFilter {

    private final PerfRegistry registry;

    /** Requests slower than this are logged individually, with the last SQL seen. */
    private static final long SLOW_MS = 1000;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        QueryCountInspector.reset();
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long millis  = (System.nanoTime() - start) / 1_000_000;
            int  queries = QueryCountInspector.count();

            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern != null ? pattern.toString() : request.getRequestURI();
            String key = request.getMethod() + " " + route;

            registry.record(key, millis, queries);

            if (millis >= SLOW_MS) {
                log.warn("[PERF] SLOW {} | {}ms | {} queries | lastSql={}",
                        key, millis, queries, abbreviate(QueryCountInspector.lastStatement()));
            } else {
                log.debug("[PERF] {} | {}ms | {} queries", key, millis, queries);
            }

            // Tomcat reuses threads — never leave counters behind.
            QueryCountInspector.clear();
        }
    }

    private static String abbreviate(String sql) {
        if (sql == null) return "-";
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}