package com.kashi.grc.common.perf;

import com.kashi.grc.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads out the latency profile collected by RequestPerfFilter.
 *
 * WORKFLOW:
 *   1. Set kashi.perf.enabled=true and restart.
 *   2. DELETE /v1/admin/perf/report        — clear any warm-up noise.
 *   3. Click through the app normally, or run the smoke script.
 *   4. GET /v1/admin/perf/report           — ranked worst-first.
 *
 * Sits under /v1/admin/**, which SecurityConfig already restricts to
 * hasAuthority("SIDE_SYSTEM"), so no additional guard is needed here.
 *
 * The whole package is @ConditionalOnProperty — with the flag off, none of these
 * beans are created and there is zero overhead.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Admin — Performance")
@ConditionalOnProperty(name = "kashi.perf.enabled", havingValue = "true")
public class PerfController {

    private final PerfRegistry registry;

    @GetMapping("/v1/admin/perf/report")
    @Operation(summary = "Latency + query-count profile per endpoint, worst p95 first")
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(
            @RequestParam(required = false, defaultValue = "0") long minMs,
            @RequestParam(required = false, defaultValue = "0") long minQueries) {

        List<PerfRegistry.EndpointStats> all = registry.report().stream()
                .filter(s -> s.getP95Ms() >= minMs)
                .filter(s -> s.getAvgQueries() >= minQueries)
                .toList();

        // Endpoints whose query count alone proves an N+1, regardless of how fast
        // the DB happened to be on this run.
        List<PerfRegistry.EndpointStats> likelyNPlusOne = all.stream()
                .filter(s -> s.getAvgQueries() >= 30)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpoints", all);
        body.put("likelyNPlusOne", likelyNPlusOne);
        body.put("totalEndpointsSeen", all.size());
        body.put("hint", "avgQueries in the tens or hundreds means batch the reads; "
                + "high p95Ms with low avgQueries means index or payload size.");
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @GetMapping(value = "/v1/admin/perf/report.csv", produces = "text/csv")
    @Operation(summary = "Same profile as CSV — paste straight into a sheet")
    public ResponseEntity<String> reportCsv() {
        StringBuilder sb = new StringBuilder("endpoint,calls,avgMs,p95Ms,maxMs,avgQueries,maxQueries,totalMs\n");
        for (PerfRegistry.EndpointStats s : registry.report()) {
            sb.append('"').append(s.getEndpoint()).append('"').append(',')
                    .append(s.getCalls()).append(',')
                    .append(s.getAvgMs()).append(',')
                    .append(s.getP95Ms()).append(',')
                    .append(s.getMaxMs()).append(',')
                    .append(s.getAvgQueries()).append(',')
                    .append(s.getMaxQueries()).append(',')
                    .append(s.getTotalMs()).append('\n');
        }
        return ResponseEntity.ok(sb.toString());
    }

    @DeleteMapping("/v1/admin/perf/report")
    @Operation(summary = "Reset counters before a measurement run")
    public ResponseEntity<ApiResponse<String>> reset() {
        registry.reset();
        return ResponseEntity.ok(ApiResponse.success("Performance counters reset"));
    }
}