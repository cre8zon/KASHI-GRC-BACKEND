package com.kashi.grc.integration.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.integration.domain.IntegrationConfig;
import com.kashi.grc.integration.domain.IntegrationRun;
import com.kashi.grc.integration.domain.TenantIntegrationCheck;
import com.kashi.grc.integration.repository.EngagementIntegrationSnapshotRepository;
import com.kashi.grc.integration.repository.IntegrationConfigRepository;
import com.kashi.grc.integration.repository.IntegrationRunRepository;
import com.kashi.grc.integration.repository.TenantIntegrationCheckRepository;
import com.kashi.grc.integration.service.IntegrationRunner;
import com.kashi.grc.integration.service.TenantIntegrationCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * IntegrationController — manage connected integrations and trigger checks.
 *
 * GET    /v1/integrations/catalog                              — all available integration types + their checks
 * GET    /v1/integrations/connected                           — tenant's connected integrations
 * POST   /v1/integrations/{key}/connect                       — connect an integration (save auth + snapshot checks)
 * DELETE /v1/integrations/{key}                               — disconnect (deactivate, keeps history)
 * GET    /v1/integrations/{key}/checks                        — tenant's check instances for this integration
 * PUT    /v1/integrations/{key}/checks/{checkKey}             — customise a tenant check instance
 * POST   /v1/integrations/{key}/checks/{checkKey}/run         — manually trigger a check
 * POST   /v1/integrations/{key}/checks/run-all                — trigger every active check now
 * GET    /v1/integrations/runs                                — run history for this tenant
 *          ?checkKey=AWS_S3_ENCRYPTION   — one check
 *          ?integrationKey=AWS           — every check under one integration
 * GET    /v1/integrations/runs/{id}                           — single run detail with raw payload
 */
@RestController
@RequestMapping("/v1/integrations")
@lombok.extern.slf4j.Slf4j
@Tag(name = "Integrations", description = "Automated compliance evidence collection")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationConfigRepository      configRepo;
    private final TenantIntegrationCheckRepository tenantCheckRepo;
    private final IntegrationRunRepository         runRepo;
    private final IntegrationRunner                runner;
    private final TenantIntegrationCheckService    tenantCheckService;
    private final UtilityService                   utilityService;
    private final EngagementIntegrationSnapshotRepository engagementSnapshotRepo;

    @GetMapping("/catalog")
    @Operation(summary = "List all available integrations with their checks")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> catalog() {
        return ResponseEntity.ok(ApiResponse.success(buildCatalog()));
    }

    @GetMapping("/connected")
    @Operation(summary = "List this tenant's connected integrations with status")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> connected() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<Map<String, Object>> result = new ArrayList<>();

        // BATCHED — was 4 count queries PER connected integration (via
        // tenantCheckService.getStats). getStatsForTenant does it in one.
        Map<String, Map<String, Object>> statsByKey = tenantCheckService.getStatsForTenant(tenantId);

        configRepo.findByTenantId(tenantId).forEach(config -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",             config.getId());
            m.put("integrationKey", config.getIntegrationKey());
            m.put("displayName",    config.getDisplayName());
            m.put("isActive",       config.isActive());
            m.put("lastRunAt",      config.getLastRunAt());
            m.put("lastRunStatus",  config.getLastRunStatus());
            // Use tenant check stats, not global library count
            m.put("checksStats",    statsByKey.getOrDefault(config.getIntegrationKey(), Map.of(
                    "totalChecks", 0L, "passing", 0L, "failing", 0L, "neverRun", 0L)));
            result.add(m);
        });

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{key}/connect")
    @Operation(summary = "Connect an integration — saves auth config and snapshots checks into tenant layer")
    public ResponseEntity<ApiResponse<Map<String, Object>>> connect(
            @PathVariable String key,
            @RequestBody Map<String, Object> body) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // ADDED: guard — reject connect if auth config is missing or entirely blank
        Object rawAuth = body.get("authConfig");
        if (rawAuth == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("MISSING_AUTH_CONFIG",
                            "Auth credentials are required to connect this integration."));
        }
        if (rawAuth instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> authMap = (Map<String, Object>) rawAuth;
            boolean allBlank = authMap.isEmpty() || authMap.values().stream()
                    .allMatch(v -> v == null || v.toString().isBlank());
            if (allBlank) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("MISSING_AUTH_CONFIG",
                                "Please fill in all required credentials before connecting."));
            }
        }

        String authConfigJson;
        try {
            authConfigJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(rawAuth);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_AUTH_CONFIG", "Invalid auth config"));
        }

        // Upsert IntegrationConfig (auth credentials)
        IntegrationConfig config = configRepo
                .findByTenantIdAndIntegrationKey(tenantId, key.toUpperCase())
                .map(existing -> {
                    existing.setAuthConfig(authConfigJson); // TODO: encrypt
                    existing.setActive(true);
                    existing.setDisplayName((String) body.getOrDefault("displayName", key + " Integration"));
                    return existing;
                })
                .orElseGet(() -> IntegrationConfig.builder()
                        .tenantId(tenantId)
                        .integrationKey(key.toUpperCase())
                        .displayName((String) body.getOrDefault("displayName", key + " Integration"))
                        .authConfig(authConfigJson) // TODO: encrypt before saving
                        .isActive(true)
                        .build());
        configRepo.save(config);

        // Snapshot global checks into tenant layer (creates TenantIntegrationCheck rows)
        int checksActivated = tenantCheckService.activateForTenant(key.toUpperCase(), tenantId);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(Map.of(
                "id",               config.getId(),
                "integrationKey",   config.getIntegrationKey(),
                "status",           "connected",
                "checksActivated",  checksActivated
        )));
    }

    @DeleteMapping("/{key}")
    @Operation(summary = "Disconnect an integration — deactivates checks and auth, keeps run history")
    public ResponseEntity<ApiResponse<Void>> disconnect(@PathVariable String key) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // Deactivate auth config
        configRepo.findByTenantIdAndIntegrationKey(tenantId, key.toUpperCase())
                .ifPresent(config -> {
                    config.setActive(false);
                    configRepo.save(config);
                });

        // Deactivate tenant check instances (preserves history)
        tenantCheckService.deactivateForTenant(key.toUpperCase(), tenantId);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/{key}/checks")
    @Operation(summary = "List tenant's check instances for this integration with current status")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listChecks(
            @PathVariable String key) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<Map<String, Object>> result = new ArrayList<>();

        tenantCheckService.getActiveChecks(tenantId, key.toUpperCase()).forEach(check -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",                  check.getId());
            m.put("checkKey",            check.getCheckKey());
            m.put("integrationKey",      check.getIntegrationKey());
            m.put("displayName",         check.getDisplayName());
            m.put("description",         check.getDescription());
            m.put("controlTag",          check.getControlTag());
            m.put("runFrequency",        check.getRunFrequency());
            m.put("isActive",            check.isActive());
            m.put("lastRunAt",           check.getLastRunAt());
            m.put("lastRunStatus",       check.getLastRunStatus());
            m.put("lastRunSummary",      check.getLastRunSummary());
            m.put("nextRunAt",           check.getNextRunAt());
            m.put("totalRunCount",       check.getTotalRunCount());
            m.put("hasCustomConfig",     check.getCheckConfigJson() != null);
            m.put("hasCustomCriteria",   check.getPassCriteriaJson() != null);
            m.put("lastCustomisedAt",    check.getLastCustomisedAt());
            result.add(m);
        });

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PutMapping("/{key}/checks/{checkKey}")
    @Operation(summary = "Customise a tenant check instance — override config, criteria, frequency")
    public ResponseEntity<ApiResponse<Map<String, Object>>> customiseCheck(
            @PathVariable String key,
            @PathVariable String checkKey,
            @RequestBody Map<String, Object> overrides) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        TenantIntegrationCheck updated = tenantCheckService.customise(
                tenantId, key.toUpperCase(), checkKey, overrides);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "id",               updated.getId(),
                "checkKey",         updated.getCheckKey(),
                "lastCustomisedAt", updated.getLastCustomisedAt()
        )));
    }

    @PostMapping("/{key}/checks/{checkKey}/run")
    @Operation(summary = "Manually trigger a specific check run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerRun(
            @PathVariable String key,
            @PathVariable String checkKey) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        IntegrationConfig config = configRepo
                .findByTenantIdAndIntegrationKey(tenantId, key.toUpperCase())
                .orElseThrow(() -> new com.kashi.grc.common.exception.ResourceNotFoundException(
                        "IntegrationConfig", 0L));

        IntegrationRun run = runner.triggerManual(config.getId(), checkKey);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "runId",            run.getId(),
                "result",           run.getResult(),
                "resultSummary",    run.getResultSummary() != null ? run.getResultSummary() : "",
                "durationMs",       run.getDurationMs() != null ? run.getDurationMs() : 0,
                "evidenceRecordId", run.getEvidenceRecordId() != null ? run.getEvidenceRecordId() : ""
        )));
    }

    @PostMapping("/{key}/checks/run-all")
    @Operation(summary = "Run every active check for this integration now")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerRunAll(@PathVariable String key) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        IntegrationConfig config = configRepo
                .findByTenantIdAndIntegrationKey(tenantId, key.toUpperCase())
                .orElseThrow(() -> new com.kashi.grc.common.exception.ResourceNotFoundException(
                        "IntegrationConfig", 0L));

        // Each check runs in its own transaction via triggerManual, so one
        // failing check cannot roll back the others. nextRunAt is deliberately
        // ignored — this is an explicit user action, not the scheduler.
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        int ok = 0, failed = 0;

        for (var tenantCheck : tenantCheckRepo
                .findByIntegrationKeyAndTenantIdAndIsActiveTrue(config.getIntegrationKey(), tenantId)) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("checkKey", tenantCheck.getCheckKey());
            try {
                IntegrationRun run = runner.triggerManual(config.getId(), tenantCheck.getCheckKey());
                r.put("result",        run.getResult());
                r.put("resultSummary", run.getResultSummary() != null ? run.getResultSummary() : "");
                r.put("runId",         run.getId());
                ok++;
            } catch (Exception e) {
                log.warn("[INTEGRATION] Run-all: {} failed — {}", tenantCheck.getCheckKey(), e.getMessage());
                r.put("result",        "ERROR");
                r.put("resultSummary", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                failed++;
            }
            results.add(r);
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("integrationKey", config.getIntegrationKey());
        body.put("total",   results.size());
        body.put("ran",     ok);
        body.put("errored", failed);
        body.put("checks",  results);
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @GetMapping("/runs")
    @Operation(summary = "Integration run history for this tenant")
    public ResponseEntity<ApiResponse<List<IntegrationRun>>> runs(
            @RequestParam(required = false) String checkKey,
            @RequestParam(required = false) String integrationKey,
            @RequestParam(defaultValue = "50") int limit) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // checkKey filters one check (AWS_S3_ENCRYPTION); integrationKey filters
        // every check under one integration (AWS). The panel previously passed the
        // integration key into checkKey, which matched nothing — check_key values
        // are AWS_S3_ENCRYPTION etc., never bare AWS.
        List<IntegrationRun> runs;
        if (checkKey != null && !checkKey.isBlank()) {
            runs = runRepo.findByTenantIdAndCheckKeyOrderByRunAtDesc(tenantId, checkKey);
        } else if (integrationKey != null && !integrationKey.isBlank()) {
            runs = configRepo
                    .findByTenantIdAndIntegrationKey(tenantId, integrationKey.toUpperCase())
                    .map(cfg -> runRepo.findTop50ByTenantIdAndIntegrationConfigIdOrderByRunAtDesc(
                            tenantId, cfg.getId()))
                    .orElseGet(java.util.List::of);
        } else {
            runs = runRepo.findTop50ByTenantIdOrderByRunAtDesc(tenantId);
        }
        return ResponseEntity.ok(ApiResponse.success(runs));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Single integration run detail")
    public ResponseEntity<ApiResponse<IntegrationRun>> getRun(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        IntegrationRun run = runRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new com.kashi.grc.common.exception.ResourceNotFoundException(
                        "IntegrationRun", id));
        return ResponseEntity.ok(ApiResponse.success(run));
    }

    /**
     * NOTE ON THE PATH: this controller carries a class-level
     * RequestMapping("/v1/integrations"), which Spring prepends to EVERY
     * method mapping — a leading slash does not escape it. The old value
     * "/v1/audit/engagements/{id}/integration-snapshots" therefore resolved to
     * /v1/integrations/v1/audit/engagements/{id}/integration-snapshots and the
     * URL the frontend called fell through to the static resource handler,
     * throwing NoResourceFoundException on every request.
     */
    @GetMapping("/engagements/{engagementId}/snapshots")
    @Operation(summary = "List engagement-scoped integration snapshots — one per AUTOMATED test instance")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getEngagementSnapshots(
            @PathVariable Long engagementId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        engagementSnapshotRepo
                .findByEngagementIdAndTenantId(engagementId, tenantId)
                .forEach(snap -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id",                  snap.getId());
                    m.put("engagementId",         snap.getEngagementId());
                    m.put("testInstanceId",       snap.getTestInstanceId());
                    m.put("checkKey",             snap.getCheckKey());
                    m.put("integrationKey",       snap.getIntegrationKey());
                    m.put("controlTagSnapshot",   snap.getControlTagSnapshot());
                    m.put("displayNameSnapshot",  snap.getDisplayNameSnapshot());
                    m.put("runFrequencySnapshot", snap.getRunFrequencySnapshot());
                    m.put("isActive",             snap.isActive());
                    m.put("lastResult",           snap.getLastResult());
                    m.put("lastResultSummary",    snap.getLastResultSummary());
                    m.put("lastRunAt",            snap.getLastRunAt());
                    m.put("lastEvidenceRecordId", snap.getLastEvidenceRecordId());
                    m.put("lastIntegrationRunId", snap.getLastIntegrationRunId());
                    m.put("runCount",             snap.getRunCount());
                    m.put("snapshottedAt",        snap.getSnapshottedAt());
                    result.add(m);
                });

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── Catalog builder ───────────────────────────────────────────────────────

    private List<Map<String, Object>> buildCatalog() {
        return List.of(
                integration("OKTA", "Okta", "Identity & MFA", "okta-logo.svg",
                        List.of("OKTA_ADMIN_MFA","OKTA_USER_MFA","OKTA_INACTIVE_ACCOUNTS","OKTA_SSO_ENFORCED","OKTA_PASSWORD_POLICY")),
                integration("ZOHO", "Zoho", "Identity & MFA", "zoho-logo.svg",
                        List.of("ZOHO_ADMIN_MFA","ZOHO_USER_MFA")),
                integration("MICROSOFT", "Microsoft Entra ID", "Identity & MFA", "microsoft-logo.svg",
                        List.of("MICROSOFT_ADMIN_MFA","MICROSOFT_USER_MFA")),
                integration("AWS", "Amazon Web Services", "Cloud Infrastructure", "aws-logo.svg",
                        List.of("AWS_CLOUDTRAIL_ENABLED","AWS_ROOT_MFA","AWS_S3_ENCRYPTION","AWS_S3_PUBLIC_ACCESS_BLOCK","AWS_IAM_PASSWORD_POLICY","AWS_GUARDDUTY_ENABLED")),
                integration("GITHUB", "GitHub", "Source Code Management", "github-logo.svg",
                        List.of("GITHUB_BRANCH_PROTECTION","GITHUB_2FA_ORG","GITHUB_PRIVATE_REPOS")),
                integration("AZURE", "Microsoft Azure", "Cloud & Identity", "azure-logo.svg",
                        List.of("AZURE_AD_MFA","AZURE_DEFENDER_ENABLED","AZURE_STORAGE_ENCRYPTION")),
                integration("GOOGLE_WORKSPACE", "Google Workspace", "Productivity & Identity", "google-logo.svg",
                        List.of("GWS_ADMIN_2SV","GWS_2SV","GWS_DRIVE_SHARING"))
        );
    }

    private Map<String, Object> integration(String key, String name, String category,
                                            String logo, List<String> checks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key); m.put("name", name);
        m.put("category", category); m.put("logo", logo);
        m.put("checksCount", checks.size()); m.put("checks", checks);
        return m;
    }
}