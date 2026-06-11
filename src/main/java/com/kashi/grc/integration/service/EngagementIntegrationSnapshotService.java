package com.kashi.grc.integration.service;

import com.kashi.grc.audit.domain.AuditTestInstance;
import com.kashi.grc.audit.repository.AuditTestInstanceRepository;
import com.kashi.grc.audit.service.AuditTestPolicySnapshotService;
import com.kashi.grc.integration.domain.EngagementIntegrationSnapshot;
import com.kashi.grc.integration.domain.IntegrationCheckConfig;
import com.kashi.grc.integration.repository.EngagementIntegrationSnapshotRepository;
import com.kashi.grc.integration.repository.IntegrationCheckConfigRepository;
import com.kashi.grc.integration.repository.TenantIntegrationCheckRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * EngagementIntegrationSnapshotService — manages the engagement-scoped integration
 * snapshot table (engagement_integration_snapshots).
 *
 * ── TWO RESPONSIBILITIES ──────────────────────────────────────────────────────
 *
 * 1. SNAPSHOT CREATION (called from AuditTestPolicySnapshotService at engagement start):
 *    For every AUTOMATED AuditTestInstance created in an engagement, look up the
 *    IntegrationCheckConfig whose checkKey matches automationKeySnapshot, and create
 *    one EngagementIntegrationSnapshot row. This is the bridge that says:
 *      "For engagement E, AuditTestInstance T is satisfied by check C"
 *
 * 2. RESULT RECORDING (called from IntegrationRunner after each check run):
 *    Find all active snapshots for (checkKey, tenantId), update their lastResult /
 *    lastRunAt / runCount, and push the result directly to the linked AuditTestInstance.
 *    This replaces the broad controlTag-based propagation for AUTOMATED tests —
 *    only the specific test instance that owns this check gets updated.
 *
 * ── WHY NOT JUST USE CONTROL TAG ─────────────────────────────────────────────
 * controlTagSnapshot (e.g. "MFA_ADMIN") is intentionally coarse — it groups controls
 * that cover the same DOMAIN. But two controls can share the same tag while being
 * tested by completely different checks:
 *   - "Admin MFA in Okta"  → checked by OKTA_ADMIN_MFA
 *   - "Admin MFA for AWS"  → checked by AWS_ROOT_MFA
 *
 * Tag-only matching would send Okta's check result to the AWS test and vice versa,
 * producing incorrect PASS results. automationKeySnapshot is the precise identifier
 * and this service uses it exclusively for AUTOMATED tests.
 *
 * MANUAL tests continue to use tag-based EvidenceReuseEngine propagation since
 * they have no automationKey.
 */
@Slf4j
@Service
public class EngagementIntegrationSnapshotService {

    private final EngagementIntegrationSnapshotRepository snapshotRepo;
    private final IntegrationCheckConfigRepository         checkConfigRepo;      // global library — fallback only
    private final TenantIntegrationCheckRepository         tenantCheckRepo;      // preferred: tenant layer
    private final AuditTestInstanceRepository              testInstanceRepo;
    private final AuditTestPolicySnapshotService           snapshotService;

    /**
     * Explicit constructor — @Lazy on snapshotService breaks the cycle:
     *   AuditTestPolicySnapshotService → EngagementIntegrationSnapshotService
     *                                  → AuditTestPolicySnapshotService  ← cycle
     *
     * @Lazy tells Spring to inject a proxy here and only resolve the real bean
     * on first method call, by which time both beans are fully initialised.
     */
    @Autowired
    public EngagementIntegrationSnapshotService(
            EngagementIntegrationSnapshotRepository snapshotRepo,
            IntegrationCheckConfigRepository checkConfigRepo,
            TenantIntegrationCheckRepository tenantCheckRepo,
            AuditTestInstanceRepository testInstanceRepo,
            @Lazy AuditTestPolicySnapshotService snapshotService) {
        this.snapshotRepo    = snapshotRepo;
        this.checkConfigRepo = checkConfigRepo;
        this.tenantCheckRepo = tenantCheckRepo;
        this.testInstanceRepo = testInstanceRepo;
        this.snapshotService  = snapshotService;
    }

    // ── 1. SNAPSHOT CREATION ──────────────────────────────────────────────────

    /**
     * Called from AuditTestPolicySnapshotService.snapshotTests() after all
     * AuditTestInstance rows are created for an engagement.
     *
     * For each AUTOMATED test instance, finds its matching IntegrationCheckConfig
     * by automationKeySnapshot and creates one EngagementIntegrationSnapshot.
     *
     * MANUAL test instances (automationTypeSnapshot != "AUTOMATED") are skipped —
     * they have no integration check and rely on EvidenceReuseEngine tag matching.
     *
     * @param engagementId  the engagement just created
     * @param tenantId      for isolation
     */
    @Transactional
    public void snapshotForEngagement(Long engagementId, Long tenantId) {
        List<AuditTestInstance> allTests =
                testInstanceRepo.findByEngagementIdOrderByTestNameSnapshotAsc(engagementId);

        int created = 0;
        int skipped = 0;

        for (AuditTestInstance test : allTests) {

            // Only AUTOMATED tests get a snapshot — MANUAL and HYBRID use tag matching
            if (!"AUTOMATED".equalsIgnoreCase(test.getAutomationTypeSnapshot())) {
                continue;
            }

            String checkKey = test.getAutomationKeySnapshot();
            if (checkKey == null || checkKey.isBlank()) {
                log.warn("[EIS] AUTOMATED test has no automationKeySnapshot | testInstanceId={} " +
                                "testName={} — skipping snapshot. Set automationKey on the library test.",
                        test.getId(), test.getTestNameSnapshot());
                skipped++;
                continue;
            }

            // Find the matching check config — try tenant-specific first, fall back to global
            Optional<CheckConfigView> checkConfigOpt =
                    findCheckConfig(checkKey, tenantId);

            if (checkConfigOpt.isEmpty()) {
                log.warn("[EIS] No IntegrationCheckConfig found for checkKey={} tenantId={} " +
                                "| testInstanceId={} — snapshot skipped. Add the check config first.",
                        checkKey, tenantId, test.getId());
                skipped++;
                continue;
            }

            CheckConfigView checkConfig = checkConfigOpt.get();

            // Idempotency — don't create duplicates if snapshotting is called twice
            if (snapshotRepo.existsByEngagementIdAndTestInstanceIdAndCheckKey(
                    engagementId, test.getId(), checkKey)) {
                log.debug("[EIS] Snapshot already exists | engagementId={} testInstanceId={} checkKey={}",
                        engagementId, test.getId(), checkKey);
                continue;
            }

            EngagementIntegrationSnapshot snapshot = EngagementIntegrationSnapshot.builder()
                    .tenantId(tenantId)
                    .engagementId(engagementId)
                    .testInstanceId(test.getId())
                    .checkKey(checkKey)
                    .integrationKey(checkConfig.integrationKey())
                    .controlTagSnapshot(test.getControlTagSnapshot())
                    .displayNameSnapshot(checkConfig.displayName())
                    .runFrequencySnapshot(checkConfig.runFrequency())
                    .isActive(true)
                    .lastResult("NOT_RUN")
                    .runCount(0)
                    .snapshottedAt(LocalDateTime.now())
                    .originalCheckConfigId(checkConfig.id())
                    .build();

            snapshotRepo.save(snapshot);
            created++;

            log.debug("[EIS] Snapshot created | engagementId={} testInstanceId={} checkKey={} integration={}",
                    engagementId, test.getId(), checkKey, checkConfig.integrationKey());
        }

        log.info("[EIS] Snapshot complete | engagementId={} tenantId={} created={} skipped={}",
                engagementId, tenantId, created, skipped);
    }

    // ── 2. RESULT RECORDING ───────────────────────────────────────────────────

    /**
     * Called by IntegrationRunner after each check execution.
     *
     * Finds all active EngagementIntegrationSnapshot rows for (checkKey, tenantId),
     * updates their state, and pushes the result directly to the linked AuditTestInstance.
     *
     * This replaces tag-based propagation for AUTOMATED tests — only the exact
     * test instance that declared this checkKey gets updated.
     *
     * @param checkKey        e.g. "OKTA_ADMIN_MFA"
     * @param tenantId        the tenant whose engagement(s) are active
     * @param isPass          true = PASS, false = FAIL
     * @param resultSummary   human-readable description from the check result
     * @param evidenceRecordId the EvidenceRecord created by the runner for this run
     * @param integrationRunId the IntegrationRun id for traceability
     */
    @Transactional
    public void recordResult(String checkKey, Long tenantId, boolean isPass,
                             String resultSummary, Long evidenceRecordId, Long integrationRunId) {

        List<EngagementIntegrationSnapshot> snapshots =
                snapshotRepo.findByCheckKeyAndTenantIdAndIsActiveTrue(checkKey, tenantId);

        if (snapshots.isEmpty()) {
            log.debug("[EIS] No active snapshots for checkKey={} tenantId={} — nothing to update",
                    checkKey, tenantId);
            return;
        }

        String result = isPass ? "PASS" : "FAIL";
        LocalDateTime now = LocalDateTime.now();

        for (EngagementIntegrationSnapshot snapshot : snapshots) {
            // Update snapshot state
            snapshot.setLastResult(result);
            snapshot.setLastResultSummary(resultSummary);
            snapshot.setLastRunAt(now);
            snapshot.setLastEvidenceRecordId(evidenceRecordId);
            snapshot.setLastIntegrationRunId(integrationRunId);
            snapshot.setRunCount(snapshot.getRunCount() + 1);
            snapshotRepo.save(snapshot);

            // Push result directly to the linked AuditTestInstance
            pushResultToTestInstance(snapshot, isPass, resultSummary, now);

            log.info("[EIS] Result recorded | checkKey={} engagementId={} testInstanceId={} result={} runCount={}",
                    checkKey, snapshot.getEngagementId(), snapshot.getTestInstanceId(),
                    result, snapshot.getRunCount());
        }
    }

    /**
     * Deactivates all snapshots for an engagement.
     * Called when an engagement is CLOSED or CANCELLED so the integration runner
     * stops feeding results into finished engagements.
     */
    @Transactional
    public void deactivateForEngagement(Long engagementId, Long tenantId) {
        int count = snapshotRepo.deactivateByEngagementId(engagementId, tenantId);
        log.info("[EIS] Deactivated {} snapshots | engagementId={} tenantId={}",
                count, engagementId, tenantId);
    }

    /**
     * Returns all snapshots for an engagement — used by the engagement detail
     * "Integrations" section to show automated check status per test.
     */
    public List<EngagementIntegrationSnapshot> getForEngagement(Long engagementId, Long tenantId) {
        return snapshotRepo.findByEngagementIdAndTenantId(engagementId, tenantId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Finds the check config for a given checkKey, for a specific tenant.
     * Priority: TenantIntegrationCheck (tenant layer) > IntegrationCheckConfig (global library).
     *
     * Returns a unified view as a simple record so the caller doesn't need to care
     * which layer it came from. Tenant layer is always preferred — it contains
     * the customised checkConfigJson and passCriteriaJson for this tenant.
     * Falls back to global library only if no tenant instance exists yet.
     */
    private Optional<CheckConfigView> findCheckConfig(String checkKey, Long tenantId) {
        // Try tenant layer first
        Optional<com.kashi.grc.integration.domain.TenantIntegrationCheck> tenantCheck =
                tenantCheckRepo.findByTenantIdAndCheckKey(tenantId, checkKey);

        if (tenantCheck.isPresent()) {
            var tc = tenantCheck.get();
            return Optional.of(new CheckConfigView(
                    tc.getId(), tc.getIntegrationKey(), tc.getCheckKey(),
                    tc.getDisplayName(), tc.getControlTag(), tc.getRunFrequency()
            ));
        }

        // Fallback: global library (tenant hasn't connected this integration yet,
        // but the test was snapshotted — unusual but handled gracefully)
        return checkConfigRepo.findAll().stream()
                .filter(c -> checkKey.equals(c.getCheckKey()))
                .filter(c -> c.getTenantId() == null || c.getTenantId().equals(tenantId))
                .min((a, b) -> {
                    if (a.getTenantId() != null && b.getTenantId() == null) return -1;
                    if (a.getTenantId() == null && b.getTenantId() != null) return 1;
                    return 0;
                })
                .map(g -> new CheckConfigView(
                        g.getId(), g.getIntegrationKey(), g.getCheckKey(),
                        g.getDisplayName(), g.getControlTag(), g.getRunFrequency()
                ));
    }

    /** Unified view returned by findCheckConfig regardless of which layer it came from. */
    private record CheckConfigView(
            Long id,
            String integrationKey,
            String checkKey,
            String displayName,
            String controlTag,
            String runFrequency
    ) {}

    /**
     * Pushes the check result directly to the AuditTestInstance linked by this snapshot.
     * Sets testResult = PASS or FAIL and adds the result summary as testerNotes.
     * Then cascades to re-derive control results for all controls linked to this test.
     */
    private void pushResultToTestInstance(EngagementIntegrationSnapshot snapshot,
                                          boolean isPass, String resultSummary, LocalDateTime now) {
        testInstanceRepo.findById(snapshot.getTestInstanceId()).ifPresent(test -> {
            AuditTestInstance.TestResult newResult = isPass
                    ? AuditTestInstance.TestResult.PASS
                    : AuditTestInstance.TestResult.FAIL;

            test.setTestResult(newResult);
            test.setRunAt(now);
            test.setRunBySystem(true);
            test.setRunByUserId(null); // system-driven, no human actor
            test.setAutomationRawResult(resultSummary);
            test.setAutomationRunAt(now);

            // For FAIL results, set failureDetail so the auditor knows what happened
            if (!isPass) {
                test.setFailureDetail(resultSummary);
            } else {
                // Clear any previous failure detail on subsequent PASS
                test.setTesterNotes("Auto-verified by " + snapshot.getCheckKey()
                        + " on " + now.toLocalDate());
            }

            testInstanceRepo.save(test);

            // Cascade — re-derive control results for all controls linked to this test
            // syncEngagementScore is called inside cascadeDeriveControlResults
            snapshotService.cascadeDeriveControlResults(test.getId(), snapshot.getTenantId());

            log.debug("[EIS] Test result pushed | testInstanceId={} result={} checkKey={}",
                    test.getId(), newResult, snapshot.getCheckKey());
        });
    }
}