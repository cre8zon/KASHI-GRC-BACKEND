package com.kashi.grc.workflow.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.ErrorResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.workflow.automation.AutomatedActionRegistry;
import com.kashi.grc.common.service.CsvImportService;
import com.kashi.grc.common.dto.CsvImportResult;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import com.kashi.grc.workflow.domain.Workflow;
import com.kashi.grc.workflow.dto.request.WorkflowCreateRequest;
import com.kashi.grc.workflow.dto.response.WorkflowResponse;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WorkflowController — blueprint management.
 *
 * WRITES: Platform Admin only (isSystemUser guard on all mutating endpoints).
 * READS:  All authenticated users can list and view workflows.
 *
 * NEW: GET /v1/workflows/automated-actions
 *   Returns all registered AutomatedActionHandler keys.
 *   Used by the blueprint admin UI to populate the "Automated Action" dropdown
 *   on SYSTEM steps. Platform Admin can also type a custom key to define a new
 *   action that can be implemented later.
 */
@Slf4j
@RestController
@RequestMapping("/v1/workflows")
@Tag(name = "Workflow Blueprints", description = "Platform Admin: create and manage global workflow blueprints")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowEngineService     service;
    private final DbRepository              dbRepository;
    private final UtilityService            utilityService;
    private final AutomatedActionRegistry   automatedActionRegistry;
    private final CsvImportService           csvImportService;

    // ── CREATE ────────────────────────────────────────────────────
    @PostMapping
    @Operation(summary = "Platform Admin: create a global workflow blueprint with steps")
    public ResponseEntity<ApiResponse<WorkflowResponse>> create(@Valid @RequestBody WorkflowCreateRequest req) {
        guardPlatformAdmin();
        log.info("[WorkflowController] CREATE | name='{}' | entityType='{}'", req.getName(), req.getEntityType());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.createWorkflow(req)));
    }

    // ── UPDATE ────────────────────────────────────────────────────
    @PutMapping("/{id}")
    @Operation(summary = "Platform Admin: update blueprint — only if no active instances running")
    public ResponseEntity<ApiResponse<WorkflowResponse>> update(
            @PathVariable Long id, @Valid @RequestBody WorkflowCreateRequest req) {
        guardPlatformAdmin();
        log.info("[WorkflowController] UPDATE | id={}", id);
        return ResponseEntity.ok(ApiResponse.success(service.updateWorkflow(id, req)));
    }

    // ── DELETE ────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    @Operation(summary = "Platform Admin: hard delete a DRAFT workflow blueprint")
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable Long id) {
        guardPlatformAdmin();
        log.info("[WorkflowController] DELETE | id={}", id);
        service.deleteWorkflow(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true, "workflowId", id)));
    }

    // ── VERSION ───────────────────────────────────────────────────
    @PostMapping("/{id}/version")
    @Operation(summary = "Platform Admin: clone blueprint as new version — starts inactive")
    public ResponseEntity<ApiResponse<WorkflowResponse>> createVersion(@PathVariable Long id) {
        guardPlatformAdmin();
        log.info("[WorkflowController] NEW VERSION | sourceId={}", id);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(service.createNewVersion(id)));
    }

    // ── ACTIVATE / DEACTIVATE ─────────────────────────────────────
    @PutMapping("/{id}/activate")
    @Operation(summary = "Platform Admin: activate workflow — validates all steps have assignments")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activate(@PathVariable Long id) {
        guardPlatformAdmin();
        log.info("[WorkflowController] ACTIVATE | id={}", id);
        service.activateWorkflow(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("workflowId", id, "isActive", true)));
    }

    @PutMapping("/{id}/deactivate")
    @Operation(summary = "Platform Admin: deactivate workflow — running instances continue unaffected")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deactivate(@PathVariable Long id) {
        guardPlatformAdmin();
        log.info("[WorkflowController] DEACTIVATE | id={}", id);
        service.deactivateWorkflow(id);
        return ResponseEntity.ok(ApiResponse.success(Map.of("workflowId", id, "isActive", false)));
    }

    // ── LIST ──────────────────────────────────────────────────────
    @GetMapping
    @Operation(summary = "List global workflow blueprints — paginated, filterable")
    public ResponseEntity<ApiResponse<PaginatedResponse<WorkflowResponse>>> list(
            @RequestParam Map<String, String> allParams) {

        boolean isSystem = utilityService.isSystemUser();
        log.debug("[WorkflowController] LIST | isSystem={}", isSystem);

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                Workflow.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<Predicate> preds = new ArrayList<>();
                    preds.add(cb.isNull(root.get("tenantId")));
                    if (!isSystem) preds.add(cb.isTrue(root.get("isActive")));
                    if (allParams.containsKey("entityType"))
                        preds.add(cb.equal(root.get("entityType"), allParams.get("entityType")));
                    return preds;
                },
                (cb, root) -> Map.of("name", root.get("name"), "entitytype", root.get("entityType")),
                w -> service.buildWorkflowResponse(w)
        )));
    }

    // ── GET SINGLE ────────────────────────────────────────────────
    @GetMapping("/{id}")
    @Operation(summary = "Get workflow blueprint with all steps, roles, and user assignments")
    public ResponseEntity<ApiResponse<WorkflowResponse>> get(@PathVariable Long id) {
        log.debug("[WorkflowController] GET | id={}", id);
        return ResponseEntity.ok(ApiResponse.success(service.buildWorkflowResponse(
                service.getWorkflowById(id))));
    }

    // ── AUTOMATED ACTIONS ─────────────────────────────────────────
    /**
     * GET /v1/workflows/automated-actions
     *
     * Returns all registered AutomatedActionHandler keys from the registry.
     * Used by the StepForm UI to populate the dropdown for SYSTEM steps.
     *
     * Platform Admin can also type a custom key not in this list — that key
     * will be stored on the step and fire when the registry has a handler
     * registered for it (i.e. after the developer implements the handler).
     *
     * No auth guard — all authenticated users can read registered actions.
     * Writing a new action requires Platform Admin (via blueprint update).
     */
    @GetMapping("/automated-actions")
    @Operation(summary = "List all registered automated action keys — for SYSTEM step configuration")
    public ResponseEntity<ApiResponse<List<String>>> getAutomatedActions() {
        List<String> keys = automatedActionRegistry.registeredKeys()
                .stream().sorted().toList();
        log.debug("[WorkflowController] AUTOMATED-ACTIONS | count={}", keys.size());
        return ResponseEntity.ok(ApiResponse.success(keys));
    }



    // ── IMPORT STEPS FROM CSV ─────────────────────────────────────
    /**
     * POST /v1/workflows/{id}/import-steps
     *
     * Bulk import workflow steps from a CSV file.
     * Auto-detects DB export format or human-authored template format.
     * Role names are resolved to IDs by the server.
     * Returns CsvImportResult with per-row log.
     *
     * Platform Admin only.
     */
    @PostMapping(value = "/{id}/import-steps", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bulk import workflow steps from CSV — auto-detects DB export or template format")
    public ResponseEntity<ApiResponse<CsvImportResult>> importSteps(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "tenantId", required = false) Long targetTenantId) {
        guardPlatformAdmin();
        // targetTenantId: the org whose roles should be used for name resolution.
        // Platform Admin's own tenantId (1) only has system roles — org roles (VENDOR_VRM etc.)
        // live under the org's tenantId. The frontend passes the org's tenantId explicitly.
        // Falls back to Platform Admin's tenantId if not provided (backward compatible).
        Long resolvedTenantId = targetTenantId != null
                ? targetTenantId
                : utilityService.getLoggedInDataContext().getTenantId();
        log.info("[WF-IMPORT] POST /{}/import-steps | file={} | targetTenantId={}", id,
                file != null ? file.getOriginalFilename() : "null", resolvedTenantId);
        CsvImportResult result = csvImportService.importWorkflowSteps(file, id, resolvedTenantId);
        if (result.isFatalError()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(new ErrorResponse("IMPORT_ERROR", result.getSummary())));
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── GUARD ─────────────────────────────────────────────────────
    private void guardPlatformAdmin() {
        if (!utilityService.isSystemUser()) {
            throw new BusinessException("FORBIDDEN",
                    "Only Platform Admin can manage workflow blueprints",
                    HttpStatus.FORBIDDEN);
        }
    }
}