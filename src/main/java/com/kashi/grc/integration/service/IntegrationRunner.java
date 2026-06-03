package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.evidence.domain.EvidenceLink;
import com.kashi.grc.evidence.domain.EvidenceRecord;
import com.kashi.grc.evidence.repository.EvidenceLinkRepository;
import com.kashi.grc.evidence.repository.EvidenceRecordRepository;
import com.kashi.grc.evidence.service.EvidenceReuseEngine;
import com.kashi.grc.integration.domain.IntegrationConfig;
import com.kashi.grc.integration.domain.IntegrationRun;
import com.kashi.grc.integration.repository.IntegrationConfigRepository;
import com.kashi.grc.integration.repository.IntegrationCheckConfigRepository;
import com.kashi.grc.integration.repository.IntegrationRunRepository;
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
 * IntegrationRunner — scheduled orchestrator for automated evidence collection.
 *
 * Runs hourly; each check determines its own frequency (HOURLY | DAILY | WEEKLY | MONTHLY).
 * The runner skips checks where nextRunAt is in the future.
 *
 * Flow for each check:
 *   1. Load IntegrationConfig (auth) for this tenant
 *   2. Find IntegrationCheck @Component by checkKey
 *   3. Run the check → CheckResult
 *   4. Create EvidenceRecord (collectionType=AUTOMATED)
 *   5. Create EvidenceLink:
 *        PASS  → AUTOMATION_VERIFIED (propagated to all matching test/control instances)
 *        FAIL  → PENDING_REVIEW (auditor must document exception)
 *   6. Update AuditTestInstance.testResult if check maps to a test
 *   7. Create IntegrationRun (immutable history)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationRunner {

    private final List<IntegrationCheck>            checks;         // Spring injects all @Component implementations
    private final IntegrationConfigRepository       configRepo;
    private final IntegrationCheckConfigRepository  checkConfigRepo;
    private final IntegrationRunRepository          runRepo;
    private final EvidenceRecordRepository          evidenceRecordRepo;
    private final EvidenceLinkRepository            evidenceLinkRepo;
    private final EvidenceReuseEngine               reuseEngine;
    private final ObjectMapper                      objectMapper;

    /** Runs every hour. Each check decides its own frequency via nextRunAt. */
    @Scheduled(fixedRate = 3_600_000)
    public void runScheduled() {
        log.info("[INTEGRATION-RUNNER] Starting scheduled run");
        LocalDateTime now = LocalDateTime.now();

        // Build map of checkKey → implementation for fast lookup
        Map<String, IntegrationCheck> checkMap = checks.stream()
                .collect(Collectors.toMap(IntegrationCheck::checkKey, Function.identity(), (a, b) -> a));

        // Find all active integration configs across all tenants
        List<IntegrationConfig> activeConfigs = configRepo.findByIsActiveTrue();

        for (IntegrationConfig config : activeConfigs) {
            // Find all checks for this integration
            checkConfigRepo
                    .findByIntegrationKeyAndIsActiveTrue(config.getIntegrationKey())
                    .forEach(checkConfig -> {
                        // Skip if not due yet
                        if (checkConfig.getNextRunAt() != null && checkConfig.getNextRunAt().isAfter(now)) {
                            return;
                        }
                        IntegrationCheck impl = checkMap.get(checkConfig.getCheckKey());
                        if (impl == null) {
                            log.warn("[INTEGRATION-RUNNER] No implementation for checkKey={}", checkConfig.getCheckKey());
                            return;
                        }
                        try {
                            runCheck(config, checkConfig, impl);
                        } catch (Exception e) {
                            log.error("[INTEGRATION-RUNNER] Unexpected error | checkKey={} | tenantId={}: {}",
                                    checkConfig.getCheckKey(), config.getTenantId(), e.getMessage());
                        }
                    });
        }
        log.info("[INTEGRATION-RUNNER] Scheduled run complete");
    }

    /** Manual trigger — called from IntegrationController */
    @Transactional
    public IntegrationRun triggerManual(Long configId, String checkKey) {
        IntegrationConfig config = configRepo.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("IntegrationConfig not found: " + configId));

        var checkConfig = checkConfigRepo.findByIntegrationKeyAndCheckKey(
                        config.getIntegrationKey(), checkKey)
                .orElseThrow(() -> new IllegalArgumentException("Check not found: " + checkKey));

        Map<String, IntegrationCheck> checkMap = checks.stream()
                .collect(Collectors.toMap(IntegrationCheck::checkKey, Function.identity(), (a, b) -> a));

        IntegrationCheck impl = checkMap.get(checkKey);
        if (impl == null) throw new IllegalStateException("No implementation for checkKey: " + checkKey);

        return runCheck(config, checkConfig, impl);
    }

    @Transactional
    protected IntegrationRun runCheck(IntegrationConfig config,
                                      com.kashi.grc.integration.domain.IntegrationCheckConfig checkConfig,
                                      IntegrationCheck impl) {
        long startMs = System.currentTimeMillis();
        LocalDateTime runAt = LocalDateTime.now();
        Long tenantId = config.getTenantId();

        log.info("[INTEGRATION] Running | checkKey={} | tenantId={}", checkConfig.getCheckKey(), tenantId);

        // Decrypt auth config (implement AES-256 decryption in production)
        String decryptedAuth = decryptAuthConfig(config.getAuthConfig());

        IntegrationCheck.CheckResult result;
        try {
            result = impl.run(decryptedAuth, checkConfig.getCheckConfigJson());
        } catch (Exception e) {
            result = IntegrationCheck.CheckResult.error("Unexpected error: " + e.getMessage(), checkConfig.getControlTag());
            log.error("[INTEGRATION] Check threw exception | checkKey={}: {}", checkConfig.getCheckKey(), e.getMessage());
        }

        int durationMs = (int)(System.currentTimeMillis() - startMs);
        boolean isPass  = result.result() == IntegrationCheck.Result.PASS;
        String status   = result.result() == IntegrationCheck.Result.ERROR ? "FAILURE" : "SUCCESS";

        // Create EvidenceRecord
        EvidenceRecord record = EvidenceRecord.builder()
                .tenantId(tenantId)
                .title(result.evidenceTitle() + " — " + runAt.toLocalDate())
                .controlTag(result.controlTag() != null ? result.controlTag().toUpperCase() : checkConfig.getControlTag())
                .collectionType(EvidenceRecord.CollectionType.AUTOMATED)
                .integrationKey(checkConfig.getCheckKey())
                .rawPayload(result.rawPayload())
                .automationResult(isPass
                        ? EvidenceRecord.AutomationResult.PASS
                        : result.result() == IntegrationCheck.Result.ERROR
                          ? EvidenceRecord.AutomationResult.ERROR
                          : EvidenceRecord.AutomationResult.FAIL)
                .automationMessage(result.summary())
                .collectedAt(runAt)
                .runFrequency(checkConfig.getRunFrequency())
                .nextRunAt(calculateNextRunAt(runAt, checkConfig.getRunFrequency()))
                .validFrom(runAt)
                .validUntil(calculateValidUntil(runAt, checkConfig.getRunFrequency()))
                .uploadedAt(runAt)
                .linkCount(0)
                .build();
        evidenceRecordRepo.save(record);

        // Propagate via EvidenceReuseEngine — creates EvidenceLinks on matching entities
        // PASS → AUTOMATION_VERIFIED links, FAIL → PENDING_REVIEW links
        if (record.getControlTag() != null && result.result() != IntegrationCheck.Result.ERROR) {
            reuseEngine.propagateAutomated(record.getId(), isPass);
        }

        // Update nextRunAt on the check config
        checkConfig.setNextRunAt(record.getNextRunAt());
        checkConfig.setLastRunAt(runAt);
        checkConfig.setLastRunStatus(isPass ? "PASS" : "FAIL");
        checkConfigRepo.save(checkConfig);

        // Update integration config last run
        config.setLastRunAt(runAt);
        config.setLastRunStatus(status);
        configRepo.save(config);

        // Create immutable run record
        IntegrationRun run = IntegrationRun.builder()
                .tenantId(tenantId)
                .integrationConfigId(config.getId())
                .checkKey(checkConfig.getCheckKey())
                .controlTag(checkConfig.getControlTag())
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

        // Update EvidenceRecord with its run ID
        record.setIntegrationRunId(run.getId());
        evidenceRecordRepo.save(record);

        log.info("[INTEGRATION] Done | checkKey={} | result={} | durationMs={} | tenantId={}",
                checkConfig.getCheckKey(), result.result(), durationMs, tenantId);

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
        // Evidence is valid for 2× the frequency period to avoid gaps during re-runs
        return switch ((frequency != null ? frequency : "DAILY").toUpperCase()) {
            case "HOURLY"  -> from.plusHours(2);
            case "DAILY"   -> from.plusDays(2);
            case "WEEKLY"  -> from.plusWeeks(2);
            case "MONTHLY" -> from.plusMonths(2);
            default        -> from.plusDays(2);
        };
    }

    /** Placeholder — implement AES-256 decryption using your key management service */
    private String decryptAuthConfig(String encrypted) {
        // TODO: integrate with AWS KMS / Azure Key Vault / HashiCorp Vault
        return encrypted; // plaintext in dev, encrypted in prod
    }
}