package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.evidence.domain.EvidenceRecord;
import com.kashi.grc.evidence.repository.EvidenceRecordRepository;
import com.kashi.grc.evidence.service.EvidenceReuseEngine;
import com.kashi.grc.integration.domain.IntegrationConfig;
import com.kashi.grc.integration.domain.IntegrationRun;
import com.kashi.grc.integration.domain.TenantIntegrationCheck;
import com.kashi.grc.integration.repository.IntegrationConfigRepository;
import com.kashi.grc.integration.repository.IntegrationRunRepository;
import com.kashi.grc.integration.repository.TenantIntegrationCheckRepository;
import com.kashi.grc.integration.spi.IntegrationCheck;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * IntegrationRunner — scheduled orchestrator for automated compliance evidence collection.
 *
 * Runs hourly; each TenantIntegrationCheck determines its own frequency via nextRunAt.
 *
 * ── THREE-LAYER ISOLATION ─────────────────────────────────────────────────────
 * Reads from TenantIntegrationCheck (tenant-owned instances) instead of the global
 * IntegrationCheckConfig library. This means:
 *   - Tenant-specific checkConfigJson and passCriteriaJson are used
 *   - Changes to the global library don't affect running tenants
 *   - Each tenant can have different frequency, config, and pass threshold
 *     for the same check
 *
 * ── RESULT ROUTING ────────────────────────────────────────────────────────────
 * After each run:
 *   1. EvidenceReuseEngine.propagateAutomated() handles AUDIT_CONTROL_INSTANCE,
 *      ASSESSMENT_QUESTION_INSTANCE, and MANUAL AuditTestInstance tag matching.
 *   2. EngagementIntegrationSnapshotService.recordResult() handles precise routing
 *      to AUTOMATED AuditTestInstance rows via checkKey -> testInstanceId mapping
 *      established at engagement snapshot time.
 *   3. TenantIntegrationCheck.lastRunStatus/lastRunAt updated for dashboard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationRunner {

    private final List<IntegrationCheck>               checks;
    private final IntegrationConfigRepository          configRepo;
    private final TenantIntegrationCheckRepository     tenantCheckRepo;
    private final IntegrationRunRepository             runRepo;
    private final EvidenceRecordRepository             evidenceRecordRepo;
    private final EvidenceReuseEngine                  reuseEngine;
    private final ObjectMapper                         objectMapper;
    private final EngagementIntegrationSnapshotService engagementSnapshotService;

    @Scheduled(fixedRate = 3_600_000)
    public void runScheduled() {
        log.info("[INTEGRATION-RUNNER] Starting scheduled run");
        LocalDateTime now = LocalDateTime.now();

        Map<String, IntegrationCheck> checkMap = checks.stream()
                .collect(Collectors.toMap(IntegrationCheck::checkKey, Function.identity(), (a, b) -> a));

        List<IntegrationConfig> activeConfigs = configRepo.findByIsActiveTrue();

        for (IntegrationConfig config : activeConfigs) {
            tenantCheckRepo
                    .findByIntegrationKeyAndTenantIdAndIsActiveTrue(
                            config.getIntegrationKey(), config.getTenantId())
                    .forEach(tenantCheck -> {
                        if (tenantCheck.getNextRunAt() != null
                                && tenantCheck.getNextRunAt().isAfter(now)) {
                            return;
                        }
                        IntegrationCheck impl = checkMap.get(tenantCheck.getCheckKey());
                        if (impl == null) {
                            log.warn("[INTEGRATION-RUNNER] No implementation for checkKey={}",
                                    tenantCheck.getCheckKey());
                            return;
                        }
                        try {
                            runCheck(config, tenantCheck, impl);
                        } catch (Exception e) {
                            log.error("[INTEGRATION-RUNNER] Error | checkKey={} tenantId={}: {}",
                                    tenantCheck.getCheckKey(), config.getTenantId(), e.getMessage());
                        }
                    });
        }
        log.info("[INTEGRATION-RUNNER] Scheduled run complete");
    }

    @Transactional
    public IntegrationRun triggerManual(Long configId, String checkKey) {
        IntegrationConfig config = configRepo.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("IntegrationConfig not found: " + configId));

        TenantIntegrationCheck tenantCheck = tenantCheckRepo
                .findByTenantIdAndIntegrationKeyAndCheckKey(
                        config.getTenantId(), config.getIntegrationKey(), checkKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "TenantIntegrationCheck not found: " + checkKey));

        Map<String, IntegrationCheck> checkMap = checks.stream()
                .collect(Collectors.toMap(IntegrationCheck::checkKey, Function.identity(), (a, b) -> a));

        IntegrationCheck impl = checkMap.get(checkKey);
        if (impl == null) throw new IllegalStateException("No implementation for checkKey: " + checkKey);

        return runCheck(config, tenantCheck, impl);
    }

    @Transactional
    protected IntegrationRun runCheck(IntegrationConfig config,
                                      TenantIntegrationCheck tenantCheck,
                                      IntegrationCheck impl) {
        long startMs = System.currentTimeMillis();
        LocalDateTime runAt = LocalDateTime.now();
        Long tenantId = config.getTenantId();

        log.info("[INTEGRATION] Running | checkKey={} | tenantId={}", tenantCheck.getCheckKey(), tenantId);

        String decryptedAuth = decryptAuthConfig(config.getAuthConfig());

        IntegrationCheck.CheckResult result;
        try {
            result = impl.run(decryptedAuth, tenantCheck.getCheckConfigJson());
        } catch (Exception e) {
            result = IntegrationCheck.CheckResult.error(
                    "Unexpected error: " + e.getMessage(), tenantCheck.getControlTag());
            log.error("[INTEGRATION] Check threw exception | checkKey={}: {}",
                    tenantCheck.getCheckKey(), e.getMessage());
        }

        int durationMs = (int)(System.currentTimeMillis() - startMs);
        boolean isPass = result.result() == IntegrationCheck.Result.PASS;
        String status  = result.result() == IntegrationCheck.Result.ERROR ? "FAILURE" : "SUCCESS";

        EvidenceRecord record = EvidenceRecord.builder()
                .tenantId(tenantId)
                .title(result.evidenceTitle() + " — " + runAt.toLocalDate())
                .controlTag(result.controlTag() != null
                        ? result.controlTag().toUpperCase() : tenantCheck.getControlTag())
                .collectionType(EvidenceRecord.CollectionType.AUTOMATED)
                .integrationKey(tenantCheck.getCheckKey())
                .rawPayload(result.rawPayload())
                .automationResult(isPass
                        ? EvidenceRecord.AutomationResult.PASS
                        : result.result() == IntegrationCheck.Result.ERROR
                          ? EvidenceRecord.AutomationResult.ERROR
                          : EvidenceRecord.AutomationResult.FAIL)
                .automationMessage(result.summary())
                .collectedAt(runAt)
                .runFrequency(tenantCheck.getRunFrequency())
                .nextRunAt(calculateNextRunAt(runAt, tenantCheck.getRunFrequency()))
                .validFrom(runAt)
                .validUntil(calculateValidUntil(runAt, tenantCheck.getRunFrequency()))
                .uploadedAt(runAt)
                .linkCount(0)
                .build();
        evidenceRecordRepo.save(record);

        // Tag-based propagation for AUDIT_CONTROL_INSTANCE, ASSESSMENT_QUESTION_INSTANCE,
        // and MANUAL/HYBRID AuditTestInstance rows (AUTOMATED test instances excluded
        // from AuditTestEvidenceMatcher — handled below).
        if (record.getControlTag() != null && result.result() != IntegrationCheck.Result.ERROR) {
            int newLinks = reuseEngine.propagate(record.getId(), isPass);
            if (newLinks == 0) {
                // Not fatal, but it is the exact signature of tag drift: the check
                // collected evidence under a tag no instance carries.
                log.warn("[KASHILINK] Automated evidence matched nothing | checkKey={} | tag={} | tenantId={}",
                        tenantCheck.getCheckKey(), record.getControlTag(), tenantId);
            }
        }

        // Precise checkKey-based routing to AUTOMATED AuditTestInstance rows
        if (result.result() != IntegrationCheck.Result.ERROR) {
            try {
                engagementSnapshotService.recordResult(
                        tenantCheck.getCheckKey(), tenantId, isPass,
                        result.summary(), record.getId(), null);
            } catch (Exception e) {
                log.error("[INTEGRATION] Failed to record engagement snapshot result | checkKey={}: {}",
                        tenantCheck.getCheckKey(), e.getMessage());
            }
        }

        // Update tenant check instance for dashboard display
        tenantCheck.setNextRunAt(calculateNextRunAt(runAt, tenantCheck.getRunFrequency()));
        tenantCheck.setLastRunAt(runAt);
        tenantCheck.setLastRunStatus(isPass ? "PASS" : "FAIL");
        tenantCheck.setLastRunSummary(result.summary());
        tenantCheck.setTotalRunCount(tenantCheck.getTotalRunCount() + 1);
        tenantCheckRepo.save(tenantCheck);

        config.setLastRunAt(runAt);
        config.setLastRunStatus(status);
        configRepo.save(config);

        IntegrationRun run = IntegrationRun.builder()
                .tenantId(tenantId)
                .integrationConfigId(config.getId())
                .checkKey(tenantCheck.getCheckKey())
                .controlTag(tenantCheck.getControlTag())
                .runAt(runAt)
                .status(status)
                .result(result.result().name())
                .resultSummary(result.summary())
                .evidenceRecordId(record.getId())
                .rawPayload(result.rawPayload())
                .durationMs(durationMs)
                .nextRunAt(record.getNextRunAt())
                .build();
        runRepo.save(run);

        record.setIntegrationRunId(run.getId());
        evidenceRecordRepo.save(record);

        log.info("[INTEGRATION] Done | checkKey={} | result={} | durationMs={} | tenantId={}",
                tenantCheck.getCheckKey(), result.result(), durationMs, tenantId);

        return run;
    }

    private LocalDateTime calculateNextRunAt(LocalDateTime from, String frequency) {
        if (frequency == null) return from.plusDays(1);
        return switch (frequency.toUpperCase()) {
            case "HOURLY"  -> from.plusHours(1);
            case "DAILY"   -> from.plusDays(1);
            case "WEEKLY"  -> from.plusWeeks(1);
            case "MONTHLY" -> from.plusMonths(1);
            default        -> from.plusDays(1);
        };
    }

    private LocalDateTime calculateValidUntil(LocalDateTime from, String frequency) {
        return switch ((frequency != null ? frequency : "DAILY").toUpperCase()) {
            case "HOURLY"  -> from.plusHours(2);
            case "DAILY"   -> from.plusDays(2);
            case "WEEKLY"  -> from.plusWeeks(2);
            case "MONTHLY" -> from.plusMonths(2);
            default        -> from.plusDays(2);
        };
    }

    private String decryptAuthConfig(String encrypted) {
        // TODO: integrate with AWS KMS / Azure Key Vault / HashiCorp Vault
        return encrypted;
    }
}