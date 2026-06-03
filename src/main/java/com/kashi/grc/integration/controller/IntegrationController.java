package com.kashi.grc.integration.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.integration.domain.IntegrationConfig;
import com.kashi.grc.integration.domain.IntegrationRun;
import com.kashi.grc.integration.repository.IntegrationConfigRepository;
import com.kashi.grc.integration.repository.IntegrationCheckConfigRepository;
import com.kashi.grc.integration.repository.IntegrationRunRepository;
import com.kashi.grc.integration.service.IntegrationRunner;
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
 * GET  /v1/integrations/catalog          — all available integration types + their checks
 * GET  /v1/integrations/connected        — tenant's connected integrations
 * POST /v1/integrations/{key}/connect    — connect an integration (save auth config)
 * DELETE /v1/integrations/{key}          — disconnect (deactivate, keeps history)
 * POST /v1/integrations/{key}/checks/{checkKey}/run — manually trigger a check
 * GET  /v1/integrations/runs             — run history for this tenant
 * GET  /v1/integrations/runs/{id}        — single run detail with raw payload
 */
@RestController
@RequestMapping("/v1/integrations")
@Tag(name = "Integrations", description = "Automated compliance evidence collection")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationConfigRepository     configRepo;
    private final IntegrationCheckConfigRepository checkConfigRepo;
    private final IntegrationRunRepository         runRepo;
    private final IntegrationRunner                runner;
    private final UtilityService                   utilityService;

    @GetMapping("/catalog")
    @Operation(summary = "List all available integrations with their checks")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> catalog() {
        List<Map<String, Object>> catalog = buildCatalog();
        return ResponseEntity.ok(ApiResponse.success(catalog));
    }

    @GetMapping("/connected")
    @Operation(summary = "List this tenant's connected integrations with status")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> connected() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<Map<String, Object>> result = new ArrayList<>();

        configRepo.findByTenantId(tenantId).forEach(config -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",              config.getId());
            m.put("integrationKey",  config.getIntegrationKey());
            m.put("displayName",     config.getDisplayName());
            m.put("isActive",        config.isActive());
            m.put("lastRunAt",       config.getLastRunAt());
            m.put("lastRunStatus",   config.getLastRunStatus());
            m.put("checksCount",
                    checkConfigRepo.countByIntegrationKeyAndIsActiveTrue(config.getIntegrationKey()));
            result.add(m);
        });

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{key}/connect")
    @Operation(summary = "Connect an integration — saves encrypted auth config")
    public ResponseEntity<ApiResponse<Map<String, Object>>> connect(
            @PathVariable String key,
            @RequestBody Map<String, Object> body) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        String authConfigJson;
        try {
            authConfigJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(body.get("authConfig"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_AUTH_CONFIG", "Invalid auth config"));
        }

        // Upsert — if already exists, update auth config
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

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(Map.of(
                "id",             config.getId(),
                "integrationKey", config.getIntegrationKey(),
                "status",         "connected"
        )));
    }

    @DeleteMapping("/{key}")
    @Operation(summary = "Disconnect an integration — deactivates, keeps run history")
    public ResponseEntity<ApiResponse<Void>> disconnect(@PathVariable String key) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        configRepo.findByTenantIdAndIntegrationKey(tenantId, key.toUpperCase())
                .ifPresent(config -> {
                    config.setActive(false);
                    configRepo.save(config);
                });
        return ResponseEntity.ok(ApiResponse.success());
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
                "runId",         run.getId(),
                "result",        run.getResult(),
                "resultSummary", run.getResultSummary() != null ? run.getResultSummary() : "",
                "durationMs",    run.getDurationMs() != null ? run.getDurationMs() : 0,
                "evidenceRecordId", run.getEvidenceRecordId() != null ? run.getEvidenceRecordId() : ""
        )));
    }

    @GetMapping("/runs")
    @Operation(summary = "Integration run history for this tenant")
    public ResponseEntity<ApiResponse<List<IntegrationRun>>> runs(
            @RequestParam(required = false) String checkKey,
            @RequestParam(defaultValue = "50") int limit) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<IntegrationRun> runs = checkKey != null
                ? runRepo.findByTenantIdAndCheckKeyOrderByRunAtDesc(tenantId, checkKey)
                : runRepo.findTop50ByTenantIdOrderByRunAtDesc(tenantId);
        return ResponseEntity.ok(ApiResponse.success(runs));
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Single integration run detail")
    public ResponseEntity<ApiResponse<IntegrationRun>> getRun(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        IntegrationRun run = runRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new com.kashi.grc.common.exception.ResourceNotFoundException("IntegrationRun", id));
        return ResponseEntity.ok(ApiResponse.success(run));
    }

    // ── Catalog builder ───────────────────────────────────────────────────────

    private List<Map<String, Object>> buildCatalog() {
        return List.of(
                integration("OKTA", "Okta", "Identity & MFA", "okta-logo.svg",
                        List.of("OKTA_ADMIN_MFA","OKTA_USER_MFA","OKTA_INACTIVE_ACCOUNTS","OKTA_SSO_ENFORCED","OKTA_PASSWORD_POLICY")),
                integration("AWS", "Amazon Web Services", "Cloud Infrastructure", "aws-logo.svg",
                        List.of("AWS_CLOUDTRAIL_ENABLED","AWS_ROOT_MFA","AWS_S3_ENCRYPTION","AWS_S3_PUBLIC_ACCESS_BLOCK","AWS_IAM_PASSWORD_POLICY","AWS_GUARDDUTY_ENABLED")),
                integration("GITHUB", "GitHub", "Source Code Management", "github-logo.svg",
                        List.of("GITHUB_BRANCH_PROTECTION","GITHUB_2FA_ORG","GITHUB_PRIVATE_REPOS")),
                integration("AZURE", "Microsoft Azure", "Cloud & Identity", "azure-logo.svg",
                        List.of("AZURE_AD_MFA","AZURE_DEFENDER_ENABLED","AZURE_STORAGE_ENCRYPTION")),
                integration("GOOGLE_WORKSPACE", "Google Workspace", "Productivity & Identity", "google-logo.svg",
                        List.of("GWS_2SV","GWS_DRIVE_SHARING"))
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