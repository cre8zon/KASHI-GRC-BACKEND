package com.kashi.grc.uiconfig.controller;

import com.kashi.grc.assessment.domain.VendorAssessment;
import com.kashi.grc.assessment.repository.VendorAssessmentRepository;
import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.repository.AuditControlInstanceRepository;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.audit.repository.AuditFindingRepository;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.vendor.domain.Vendor;
import com.kashi.grc.vendor.repository.VendorRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tenant-level statistics for the modules that had none.
 *
 * Risk, Asset, Incident, Personnel and Training each expose their own /stats on
 * their own controller. Audit, Vendor and Assessment did not: Audit had only a
 * per-engagement one, and Vendor and Assessment had nothing at all. Without
 * these three, those modules could not have a dashboard.
 *
 * ── INSTANCES, NOT LIBRARIES ──────────────────────────────────────────────
 * This is the distinction that makes these numbers either true or nonsense.
 *
 * The audit side has a LIBRARY — AuditTemplate, AuditControl, AuditSection,
 * AuditProjectTemplate — and separately has INSTANCES: AuditEngagement,
 * AuditControlInstance, AuditSectionInstance, AuditPolicyInstance. The library
 * is the catalogue of what could be audited; the instances are what is actually
 * being audited right now. Counting the library would report "212 controls"
 * whether or not a single engagement exists, which is worse than reporting
 * nothing, because it looks like work.
 *
 * Assessment is the same shape: AssessmentTemplate is the questionnaire,
 * VendorAssessment is a real questionnaire sent to a real vendor with real
 * answers. Only the second is a fact about the organisation.
 *
 * So every count below is over instances, and each is named for it.
 *
 * ── ENUM VALUES WERE READ, NOT GUESSED ────────────────────────────────────
 * Two first drafts of this file were wrong: AuditEngagement.Status has CLOSED,
 * not COMPLETED, and AuditControlInstance.TestResult has INEFFECTIVE, not FAIL.
 * Both would have compiled as far as the IDE's autocomplete and failed the
 * build, or worse, silently counted nothing had they been strings.
 *
 * ── WHY ONE CONTROLLER ────────────────────────────────────────────────────
 * These three read across modules that are not mine to change, and a dashboard
 * endpoint bolted onto AuditEngagementController would sit oddly beside its
 * engagement-scoped one. Grouping them here keeps the additions in one place
 * and out of three established controllers.
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "Dashboard statistics", description = "Tenant-level counts for Audit, Vendor and Assessment")
public class DashboardStatsController {

    private final AuditEngagementRepository      engagementRepository;
    private final AuditFindingRepository         findingRepository;
    private final AuditControlInstanceRepository controlInstanceRepository;
    private final VendorRepository               vendorRepository;
    private final VendorAssessmentRepository     assessmentRepository;
    private final UtilityService                 utilityService;
    private final com.kashi.grc.uiconfig.scheduler.MetricSnapshotScheduler snapshotScheduler;

    /**
     * Self-injected so @Cacheable actually fires.
     *
     * Spring's caching is proxy-based, exactly like @Transactional: calling
     * computeAuditStats() directly from auditStats() in the same class bypasses
     * the proxy and the annotation does nothing. AuditReferenceListCacheService
     * carries the same warning in its javadoc, and it is the single most common
     * way a @Cacheable ends up silently doing nothing.
     *
     * @Lazy breaks the circular dependency this otherwise creates at startup.
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private DashboardStatsController cache;

    /**
     * Registers these three with the nightly capture.
     *
     * The scheduler cannot call /v1/audit/stats over HTTP — it has no session,
     * and every endpoint here reads the tenant from the logged-in context. So
     * each module hands it a supplier that takes a tenant id instead, and the
     * same computation serves both the request and the history.
     *
     * @PostConstruct rather than constructor injection: the self-proxy for
     * caching is @Lazy, and touching it during construction would defeat that.
     */
    @jakarta.annotation.PostConstruct
    void registerMetricSources() {
        snapshotScheduler.register("audit",       this::computeAuditStats);
        snapshotScheduler.register("vendors",     this::computeVendorStats);
        snapshotScheduler.register("assessments", this::computeAssessmentStats);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AUDIT — engagements, findings and control instances
    // ═════════════════════════════════════════════════════════════════════════

    @GetMapping("/audit/stats")
    @Operation(summary = "Engagement, finding and control-instance counts — instances, not the library")
    public ResponseEntity<ApiResponse<Map<String, Object>>> auditStats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(cache.computeAuditStats(tenantId)));
    }

    @org.springframework.cache.annotation.Cacheable(
            cacheNames = com.kashi.grc.common.cache.CacheNames.DASHBOARD_STATS,
            key = "'auditStats:' + #tenantId")
    public Map<String, Object> computeAuditStats(Long tenantId) {

        List<AuditEngagement> engagements = engagementRepository.findAll().stream()
                .filter(e -> tenantId.equals(e.getTenantId()))
                .toList();

        List<AuditFinding> findings = findingRepository.findAll().stream()
                .filter(f -> tenantId.equals(f.getTenantId()))
                .toList();

        List<AuditControlInstance> controls = controlInstanceRepository.findAll().stream()
                .filter(c -> tenantId.equals(c.getTenantId()))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalEngagements", engagements.size());
        // CLOSED, not COMPLETED — the enum is
        // PLANNING, FIELDWORK, EVIDENCE_REVIEW, DRAFT_REPORT,
        // MANAGEMENT_RESPONSE, FINAL_REPORT, CLOSED, CANCELLED.
        // FINAL_REPORT still counts as active: the report exists but the
        // engagement is not put to bed until it is closed.
        out.put("activeEngagements", engagements.stream()
                .filter(e -> e.getStatus() != AuditEngagement.Status.CLOSED
                        && e.getStatus() != AuditEngagement.Status.CANCELLED)
                .count());
        out.put("byEngagementStatus", countBy(engagements, e -> name(e.getStatus())));

        out.put("totalFindings", findings.size());
        // Open findings are the number an auditor asks for first, and the one
        // that should be visible without opening anything.
        out.put("openFindings", findings.stream()
                .filter(f -> f.getStatus() == AuditFinding.Status.OPEN
                        || f.getStatus() == AuditFinding.Status.IN_REMEDIATION)
                .count());
        out.put("byFindingSeverity", countBy(findings, f -> name(f.getSeverity())));
        out.put("byFindingStatus",   countBy(findings, f -> name(f.getStatus())));

        // Control instances tested and failed, which is what "how is the audit
        // going" actually means — not how many controls exist in the library.
        out.put("controlsInScope", controls.size());
        // INEFFECTIVE, not FAIL — the enum is EFFECTIVE, PARTIALLY_EFFECTIVE,
        // INEFFECTIVE, NOT_APPLICABLE, NOT_TESTED. Partially effective is
        // counted separately rather than folded into either: it is the state an
        // auditor most often needs to see on its own, because it is the one
        // that becomes a finding if nobody acts.
        out.put("controlsIneffective", controls.stream()
                .filter(c -> c.getTestResult() == AuditControlInstance.TestResult.INEFFECTIVE)
                .count());
        out.put("controlsPartiallyEffective", controls.stream()
                .filter(c -> c.getTestResult() == AuditControlInstance.TestResult.PARTIALLY_EFFECTIVE)
                .count());
        out.put("controlsNotTested", controls.stream()
                .filter(c -> c.getTestResult() == AuditControlInstance.TestResult.NOT_TESTED)
                .count());
        out.put("byControlResult", countBy(controls, c -> name(c.getTestResult())));

        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VENDOR
    // ═════════════════════════════════════════════════════════════════════════

    @GetMapping("/vendors/stats")
    @Operation(summary = "Vendor counts by status and tier, and the risk-score distribution")
    public ResponseEntity<ApiResponse<Map<String, Object>>> vendorStats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(cache.computeVendorStats(tenantId)));
    }

    @org.springframework.cache.annotation.Cacheable(
            cacheNames = com.kashi.grc.common.cache.CacheNames.DASHBOARD_STATS,
            key = "'vendorStats:' + #tenantId")
    public Map<String, Object> computeVendorStats(Long tenantId) {

        List<Vendor> vendors = vendorRepository.findAll().stream()
                .filter(v -> tenantId.equals(v.getTenantId()) && !v.isDeleted())
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", vendors.size());
        out.put("active", vendors.stream().filter(v -> "ACTIVE".equalsIgnoreCase(v.getStatus())).count());
        out.put("onboarding", vendors.stream().filter(v -> "ONBOARDING".equalsIgnoreCase(v.getStatus())).count());
        out.put("byStatus", countBy(vendors, v -> v.getStatus() == null ? "UNKNOWN" : v.getStatus()));

        // Banded rather than averaged. A mean risk score across a vendor
        // portfolio is a number that moves slowly and says nothing — two
        // critical vendors and forty trivial ones average to "fine". The band
        // counts are what a third-party risk manager acts on.
        Map<String, Long> bands = new LinkedHashMap<>();
        bands.put("Critical (80+)", 0L);
        bands.put("High (60-79)",   0L);
        bands.put("Medium (40-59)", 0L);
        bands.put("Low (<40)",      0L);
        bands.put("Not scored",     0L);
        for (Vendor v : vendors) {
            BigDecimal s = v.getCurrentRiskScore();
            String key = s == null ? "Not scored"
                    : s.doubleValue() >= 80 ? "Critical (80+)"
                      : s.doubleValue() >= 60 ? "High (60-79)"
                        : s.doubleValue() >= 40 ? "Medium (40-59)"
                          : "Low (<40)";
            bands.merge(key, 1L, Long::sum);
        }
        out.put("byRiskBand", toSeries(bands));
        out.put("unscored", bands.get("Not scored"));

        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ASSESSMENT — sent questionnaires, not templates
    // ═════════════════════════════════════════════════════════════════════════

    @GetMapping("/vendor-assessments/stats")
    @Operation(summary = "Assessment instance counts — questionnaires actually sent, not templates")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assessmentStats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(cache.computeAssessmentStats(tenantId)));
    }

    @org.springframework.cache.annotation.Cacheable(
            cacheNames = com.kashi.grc.common.cache.CacheNames.DASHBOARD_STATS,
            key = "'assessmentStats:' + #tenantId")
    public Map<String, Object> computeAssessmentStats(Long tenantId) {

        List<VendorAssessment> assessments = assessmentRepository.findAll().stream()
                .filter(a -> tenantId.equals(a.getTenantId()))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", assessments.size());
        out.put("byStatus", countBy(assessments, a -> a.getStatus() == null ? "UNKNOWN" : a.getStatus()));
        out.put("byRiskRating", countBy(assessments,
                a -> a.getRiskRating() == null ? "Not rated" : a.getRiskRating()));

        long submitted = assessments.stream().filter(a -> a.getSubmittedAt() != null).count();
        out.put("submitted", submitted);
        out.put("awaitingResponse", assessments.stream()
                .filter(a -> a.getSubmittedAt() == null && a.getCompletedAt() == null)
                .count());
        out.put("completionPercent", assessments.isEmpty() ? 0
                : (int) Math.round(submitted * 100.0 / assessments.size()));

        // Remediation still open across every assessment. This is the number
        // that survives an assessment being "complete" — a finished
        // questionnaire with eleven open remediations is not finished work.
        out.put("openRemediations", assessments.stream()
                .mapToInt(a -> a.getOpenRemediationCount() == null ? 0 : a.getOpenRemediationCount())
                .sum());

        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Counts grouped by a key, emitted as [{key, value}].
     *
     * A LIST, not a map, and deliberately: the chart widgets read labelKey and
     * valueKey out of each row, and a bare map has no stable order — the same
     * donut would reshuffle its slices between refreshes.
     */
    private <T> List<Map<String, Object>> countBy(List<T> items,
                                                  java.util.function.Function<T, String> keyFn) {
        Map<String, Long> counts = items.stream()
                .collect(Collectors.groupingBy(keyFn, LinkedHashMap::new, Collectors.counting()));
        return toSeries(counts);
    }

    private List<Map<String, Object>> toSeries(Map<String, Long> counts) {
        List<Map<String, Object>> out = new ArrayList<>(counts.size());
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", e.getKey());
            row.put("value", e.getValue());
            out.add(row);
        }
        return out;
    }

    private String name(Enum<?> e) { return e == null ? "UNKNOWN" : e.name(); }
}