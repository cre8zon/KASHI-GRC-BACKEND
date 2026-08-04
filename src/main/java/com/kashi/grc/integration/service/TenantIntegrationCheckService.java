package com.kashi.grc.integration.service;

import com.kashi.grc.integration.domain.IntegrationCheckConfig;
import com.kashi.grc.integration.domain.TenantIntegrationCheck;
import com.kashi.grc.integration.repository.IntegrationCheckConfigRepository;
import com.kashi.grc.integration.repository.TenantIntegrationCheckRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TenantIntegrationCheckService — manages the tenant layer of the three-layer
 * integration check pattern.
 *
 * ── RESPONSIBILITIES ──────────────────────────────────────────────────────────
 *
 * 1. ACTIVATION: when a tenant connects an integration, snapshot all global
 *    IntegrationCheckConfig rows for that integration_key into tenant-owned
 *    TenantIntegrationCheck rows. This is the "connection time snapshot" —
 *    the tenant now owns their copy and changes to the global library don't
 *    affect them.
 *
 * 2. CUSTOMISATION: tenant can override checkConfigJson, passCriteriaJson,
 *    runFrequency, displayName on any of their check instances. The global
 *    library row is never touched.
 *
 * 3. DEACTIVATION: when a tenant disconnects an integration, mark all their
 *    TenantIntegrationCheck rows for that integration_key as isActive=false.
 *    History is preserved. EngagementIntegrationSnapshots are also deactivated.
 *
 * ── CALLED FROM ───────────────────────────────────────────────────────────────
 * IntegrationController.connect()       → activateForTenant()
 * IntegrationController.disconnect()    → deactivateForTenant()
 * IntegrationController.customiseCheck()→ customise()
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantIntegrationCheckService {

    private final TenantIntegrationCheckRepository  tenantCheckRepo;
    private final IntegrationCheckConfigRepository  globalCheckRepo;
    private final EngagementIntegrationSnapshotService engagementSnapshotService;

    // ── 1. ACTIVATION ─────────────────────────────────────────────────────────

    /**
     * Snapshots all global IntegrationCheckConfig rows for the given integration
     * into TenantIntegrationCheck rows owned by this tenant.
     *
     * Idempotent — if a row already exists for (tenant, integration, checkKey),
     * it is reactivated (isActive=true) rather than duplicated.
     *
     * Called from IntegrationController.connect() after saving IntegrationConfig.
     *
     * @param integrationKey e.g. "OKTA"
     * @param tenantId       the tenant connecting the integration
     * @return count of rows created or reactivated
     */
    @Transactional
    public int activateForTenant(String integrationKey, Long tenantId) {
        List<IntegrationCheckConfig> globalChecks =
                globalCheckRepo.findByIntegrationKeyAndIsActiveTrue(integrationKey);

        if (globalChecks.isEmpty()) {
            log.warn("[TIC] No global checks found for integrationKey={} — nothing to activate",
                    integrationKey);
            return 0;
        }

        int count = 0;
        LocalDateTime now = LocalDateTime.now();

        for (IntegrationCheckConfig global : globalChecks) {

            Optional<TenantIntegrationCheck> existing =
                    tenantCheckRepo.findByTenantIdAndIntegrationKeyAndCheckKey(
                            tenantId, integrationKey, global.getCheckKey());

            if (existing.isPresent()) {
                // Reactivate — tenant is reconnecting, preserve any customisations
                TenantIntegrationCheck check = existing.get();
                if (!check.isActive()) {
                    check.setActive(true);
                    tenantCheckRepo.save(check);
                    log.debug("[TIC] Reactivated | tenantId={} checkKey={}", tenantId, global.getCheckKey());
                    count++;
                }
                // Already active — idempotent, do nothing
            } else {
                // First connection — snapshot global definition into tenant row
                TenantIntegrationCheck check = TenantIntegrationCheck.builder()
                        .tenantId(tenantId)
                        .originalCheckConfigId(global.getId())
                        .integrationKey(global.getIntegrationKey())
                        .checkKey(global.getCheckKey())
                        .displayName(global.getDisplayName())
                        .description(global.getDescription())
                        .controlTag(global.getControlTag())
                        // Snapshot the global config — tenant can override later
                        .checkConfigJson(global.getCheckConfigJson())
                        .passCriteriaJson(global.getPassCriteriaJson())
                        .runFrequency(global.getRunFrequency())
                        .isActive(true)
                        .activatedAt(now)
                        .totalRunCount(0)
                        .build();

                tenantCheckRepo.save(check);
                log.debug("[TIC] Created | tenantId={} checkKey={} integrationKey={}",
                        tenantId, global.getCheckKey(), integrationKey);
                count++;
            }
        }

        log.info("[TIC] Activation complete | integrationKey={} tenantId={} checks={}",
                integrationKey, tenantId, count);
        return count;
    }

    // ── 2. CUSTOMISATION ──────────────────────────────────────────────────────

    /**
     * Applies tenant-specific overrides to a check instance.
     * Only the fields present in the overrides map are updated — partial update.
     *
     * Supported override keys:
     *   checkConfigJson    — tenant-specific check parameters
     *   passCriteriaJson   — tenant-specific pass threshold
     *   runFrequency       — HOURLY | DAILY | WEEKLY | MONTHLY
     *   displayName        — tenant-specific label
     *   isActive           — enable/disable this check without disconnecting integration
     *
     * @param tenantId       the tenant
     * @param integrationKey e.g. "OKTA"
     * @param checkKey       e.g. "OKTA_ADMIN_MFA"
     * @param overrides      map of field name → new value
     */
    @Transactional
    public TenantIntegrationCheck customise(Long tenantId, String integrationKey,
                                            String checkKey, Map<String, Object> overrides) {
        TenantIntegrationCheck check = tenantCheckRepo
                .findByTenantIdAndIntegrationKeyAndCheckKey(tenantId, integrationKey, checkKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No check instance for tenant=" + tenantId
                                + " integration=" + integrationKey
                                + " check=" + checkKey));

        if (overrides.containsKey("checkConfigJson"))
            check.setCheckConfigJson(overrides.get("checkConfigJson").toString());
        if (overrides.containsKey("passCriteriaJson"))
            check.setPassCriteriaJson(overrides.get("passCriteriaJson").toString());
        if (overrides.containsKey("runFrequency"))
            check.setRunFrequency(overrides.get("runFrequency").toString().toUpperCase());
        if (overrides.containsKey("displayName"))
            check.setDisplayName(overrides.get("displayName").toString());
        if (overrides.containsKey("isActive"))
            check.setActive(Boolean.parseBoolean(overrides.get("isActive").toString()));

        check.setLastCustomisedAt(LocalDateTime.now());
        tenantCheckRepo.save(check);

        log.info("[TIC] Customised | tenantId={} checkKey={} fields={}",
                tenantId, checkKey, overrides.keySet());
        return check;
    }

    // ── 3. DEACTIVATION ───────────────────────────────────────────────────────

    /**
     * Deactivates all TenantIntegrationCheck rows for a tenant's disconnected
     * integration. Run history is preserved. Active EngagementIntegrationSnapshots
     * for this integration's checks are also deactivated so future run results
     * from other tenants don't bleed into this tenant's data.
     *
     * Called from IntegrationController.disconnect().
     *
     * @param integrationKey e.g. "OKTA"
     * @param tenantId       the tenant disconnecting
     */
    @Transactional
    public void deactivateForTenant(String integrationKey, Long tenantId) {
        int count = tenantCheckRepo.deactivateByTenantAndIntegration(tenantId, integrationKey);
        log.info("[TIC] Deactivated {} check instances | integrationKey={} tenantId={}",
                count, integrationKey, tenantId);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** All active check instances for a tenant's integration — for dashboard display. */
    public List<TenantIntegrationCheck> getActiveChecks(Long tenantId, String integrationKey) {
        return tenantCheckRepo.findByIntegrationKeyAndTenantIdAndIsActiveTrue(
                integrationKey, tenantId);
    }

    /** All active check instances for a tenant across all integrations. */
    public List<TenantIntegrationCheck> getAllActiveChecks(Long tenantId) {
        return tenantCheckRepo.findByTenantIdAndIsActiveTrue(tenantId);
    }

    /**
     * Dashboard stats for a tenant's connected integration.
     * Returns pass/fail/never-run counts without loading all check rows.
     */
    public Map<String, Object> getStats(Long tenantId, String integrationKey) {
        long total    = tenantCheckRepo.countByTenantIdAndIntegrationKeyAndIsActiveTrue(tenantId, integrationKey);
        long passing  = tenantCheckRepo.countPassingByTenantAndIntegration(tenantId, integrationKey);
        long failing  = tenantCheckRepo.countFailingByTenantAndIntegration(tenantId, integrationKey);
        long neverRun = tenantCheckRepo.countNeverRunByTenantAndIntegration(tenantId, integrationKey);
        return Map.of(
                "totalChecks",  total,
                "passing",      passing,
                "failing",      failing,
                "neverRun",     neverRun
        );
    }

    /**
     * Batch counterpart to getStats — ONE query for every connected
     * integration's stats instead of 4 queries PER integration. Fixes the
     * N+1 in IntegrationController.connected(): a tenant with 10 connected
     * integrations meant 40 round trips just to render the dashboard list.
     * Returns integrationKey -> same stats map shape as getStats().
     */
    public Map<String, Map<String, Object>> getStatsForTenant(Long tenantId) {
        List<TenantIntegrationCheck> checks = tenantCheckRepo.findByTenantIdAndIsActiveTrue(tenantId);
        Map<String, Map<String, Object>> result = new java.util.HashMap<>();
        Map<String, List<TenantIntegrationCheck>> byKey = checks.stream()
                .collect(java.util.stream.Collectors.groupingBy(TenantIntegrationCheck::getIntegrationKey));
        for (var entry : byKey.entrySet()) {
            List<TenantIntegrationCheck> group = entry.getValue();
            long total    = group.size();
            long passing  = group.stream().filter(c -> "PASS".equals(c.getLastRunStatus())).count();
            long failing  = group.stream().filter(c -> "FAIL".equals(c.getLastRunStatus())).count();
            long neverRun = group.stream().filter(c -> c.getLastRunStatus() == null).count();
            result.put(entry.getKey(), Map.of(
                    "totalChecks",  total,
                    "passing",      passing,
                    "failing",      failing,
                    "neverRun",     neverRun
            ));
        }
        return result;
    }
}