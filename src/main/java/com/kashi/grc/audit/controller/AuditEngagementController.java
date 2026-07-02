package com.kashi.grc.audit.controller;

import com.kashi.grc.audit.domain.*;
import com.kashi.grc.audit.dto.request.*;
import com.kashi.grc.audit.dto.response.*;
import com.kashi.grc.audit.repository.*;
import com.kashi.grc.audit.service.AuditEngagementService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.workflow.domain.TaskInstance;
import com.kashi.grc.workflow.dto.request.StartWorkflowRequest;
import com.kashi.grc.workflow.dto.request.TaskActionRequest;
import com.kashi.grc.workflow.dto.response.WorkflowHistoryResponse;
import com.kashi.grc.workflow.enums.ActionType;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import com.kashi.grc.audit.domain.AuditFinding;
import com.kashi.grc.audit.repository.AuditFindingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * AuditEngagementController — /v1/audit/**
 *
 * Handles org-level execution: projects, project-template planning, and engagements.
 *
 * ── RESPONSIBILITIES ─────────────────────────────────────────────────────────
 *
 *   Projects (org-scoped):
 *     POST /projects            → org creates own tenant-scoped project
 *     GET  /projects            → org sees global (tenantId=null) + own projects
 *     GET  /projects/{id}       → project detail + snapshot if started
 *
 *   Project-Template planning:
 *     GET/POST/DELETE /projects/{id}/templates
 *       → org adds/removes published templates to their project plan
 *       → also works for global projects (org adopts a Platform Admin project)
 *     POST /projects/{id}/templates/{id}/start
 *       → triggers AuditEngagementService.create()
 *       → ensureProjectInstance() snapshots the project (once per project)
 *       → snapshotTemplate() creates 100% isolated engagement instances
 *
 *   Engagements (execution layer):
 *     POST /engagements         → direct engagement creation (without plan)
 *     GET  /engagements         → list with filters
 *     GET  /engagements/{id}    → detail + project snapshot
 *     PATCH /engagements/{id}/status
 *
 *   Sections & Controls (instance layer — read/write after snapshot):
 *     GET/PUT/POST on /engagements/{id}/sections/**
 *     GET/PUT     on /engagements/{id}/controls/**
 *
 * ── LIBRARY CRUD IS IN AuditLibraryController ────────────────────────────────
 *   Controls, sections, templates, global projects → /v1/audit/library/**
 *   CSV import → /v1/audit/library/templates/import
 *
 * ── ISOLATION CONTRACT ────────────────────────────────────────────────────────
 *   After snapshotTemplate() + ensureProjectInstance() fire:
 *     AuditProjectInstance              ← frozen project metadata
 *     AuditEngagementTemplateInstance   ← frozen template
 *     AuditSectionInstance tree         ← frozen sections
 *     AuditControlInstance              ← frozen controls
 *   ZERO FK between instance tables and library tables. Library edits
 *   never affect running engagements.
 */
@Slf4j
@RestController
@RequestMapping("/v1/audit")
@Tag(name = "Audit Management", description = "Org projects, engagement lifecycle, section and control execution")
@RequiredArgsConstructor
public class AuditEngagementController {

    private final AuditEngagementService                service;
    private final AuditProjectRepository                projectRepository;
    private final AuditProjectTemplateRepository        projectTemplateRepository;
    private final AuditProjectInstanceRepository        projectInstanceRepository;
    private final AuditEngagementRepository             engagementRepository;
    private final AuditSectionInstanceRepository        sectionInstanceRepository;
    private final AuditControlInstanceRepository        controlInstanceRepository;
    private final AuditFindingRepository                findingRepository;
    private final AuditTemplateRepository               templateRepository;
    private final DbRepository                          dbRepository;
    private final UtilityService                        utilityService;
    private final WorkflowEngineService                 workflowEngineService;
    private final com.kashi.grc.usermanagement.repository.RoleRepository roleRepository;
    private final TaskInstanceRepository                taskInstanceRepository;
    private final StepInstanceRepository                stepInstanceRepository;
    private final com.kashi.grc.workflow.repository.WorkflowInstanceRepository instanceRepository;
    private final com.kashi.grc.workflow.repository.WorkflowRepository workflowRepository;
    private final AuditProjectTenantAccessRepository projectTenantAccessRepository;
    private final com.kashi.grc.workflow.service.WorkflowAccessService workflowAccessService;

    // ══════════════════════════════════════════════════════════════════════════
    // PROJECTS — org-scoped creation, global + own visibility
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/projects")
    @Operation(summary = "Create an org-scoped audit project — tenantId = caller's tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createProject(
            @Valid @RequestBody AuditProjectRequest req) {

        var ctx = utilityService.getLoggedInDataContext();
        // Platform Admin (System user) creates GLOBAL projects (tenantId=null) —
        // visible to all orgs and selectable when creating a project instance.
        // Org users create tenant-scoped projects (tenantId = their tenant).
        Long tenantId = utilityService.isSystemUser() ? null : ctx.getTenantId();

        // Use MAX on ref to get a globally unique sequence — avoids collisions from count-based approach
        int year = java.time.LocalDateTime.now().getYear();
        String prefix = "PROJ-" + year + "-";
        long seq = projectRepository.findAll().stream()
                .map(p -> p.getProjectRef())
                .filter(r -> r != null && r.startsWith(prefix))
                .mapToLong(r -> { try { return Long.parseLong(r.substring(prefix.length())); } catch (Exception e) { return 0L; } })
                .max().orElse(0L) + 1;
        String ref = String.format("PROJ-%d-%04d", year, seq);

        AuditProject project = AuditProject.builder()
                .projectRef(ref)
                .tenantId(tenantId)
                .name(req.getName())
                .description(req.getDescription())
                .ownerId(req.getOwnerId() != null ? req.getOwnerId() : ctx.getId())
                .createdBy(ctx.getId())
                .plannedStart(req.getPlannedStart())
                .plannedEnd(req.getPlannedEnd())
                .status(AuditProject.Status.PLANNING)
                .publishStatus(AuditProject.PublishStatus.DRAFT)
                .visibility(AuditProject.Visibility.GLOBAL)
                .build();

        projectRepository.save(project);
        log.info("[AUDIT] Org project created | ref={} | tenantId={}", ref, tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id",         project.getId());
        result.put("projectRef", ref);
        result.put("global",     false);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    @GetMapping("/projects")
    @Operation(summary = "List projects — org users see only PUBLISHED+visible ones; system sees all")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listProjects(
            @RequestParam Map<String, String> allParams) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();
        Long tenantId    = ctx.getTenantId();

        // Org-side: projects this tenant has SPECIFIC access to
        final List<Long> specificProjectIds;
        if (isSystem) {
            specificProjectIds = List.of();
        } else {
            List<Long> ids = List.of();
            try {
                ids = projectTenantAccessRepository.findByTenantId(tenantId)
                        .stream().map(AuditProjectTenantAccess::getProjectId).toList();
            } catch (Exception e) {
                log.warn("[AUDIT] Could not load specific project access for tenant={}: {}", tenantId, e.getMessage());
            }
            specificProjectIds = ids;
        }

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AuditProject.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    var preds = new ArrayList<jakarta.persistence.criteria.Predicate>();
                    if (!isSystem) {
                        // Org users: PUBLISHED only
                        preds.add(cb.equal(root.get("publishStatus"),
                                AuditProject.PublishStatus.PUBLISHED));
                        // Tenant ownership: global (null) OR own tenant OR
                        // SPECIFIC project from another tenant where this tenant has been granted access
                        var ownershipPred = cb.or(
                                cb.isNull(root.get("tenantId")),           // platform-owned global
                                cb.equal(root.get("tenantId"), tenantId)   // own tenant projects
                        );
                        if (!specificProjectIds.isEmpty()) {
                            // Also include SPECIFIC projects from any tenant where access was granted
                            ownershipPred = cb.or(ownershipPred,
                                    root.get("id").in(specificProjectIds));
                        }
                        preds.add(ownershipPred);
                        // Visibility: GLOBAL or (SPECIFIC and this tenant has access)
                        var visibilityPred = cb.equal(root.get("visibility"),
                                AuditProject.Visibility.GLOBAL);
                        if (!specificProjectIds.isEmpty()) {
                            visibilityPred = cb.or(visibilityPred,
                                    cb.and(
                                            cb.equal(root.get("visibility"), AuditProject.Visibility.SPECIFIC),
                                            root.get("id").in(specificProjectIds)
                                    ));
                        }
                        preds.add(visibilityPred);
                    } else {
                        // System: honour explicit status/visibility filters if provided
                        if (allParams.containsKey("publishStatus"))
                            preds.add(cb.equal(root.get("publishStatus"),
                                    AuditProject.PublishStatus.valueOf(allParams.get("publishStatus").toUpperCase())));
                        if (allParams.containsKey("visibility"))
                            preds.add(cb.equal(root.get("visibility"),
                                    AuditProject.Visibility.valueOf(allParams.get("visibility").toUpperCase())));
                    }
                    if (allParams.containsKey("status"))
                        preds.add(cb.equal(root.get("status"),
                                AuditProject.Status.valueOf(allParams.get("status").toUpperCase())));
                    return preds;
                },
                (cb, root) -> Map.of(
                        "name",      root.get("name"),
                        "status",    root.get("status"),
                        "createdAt", root.get("createdAt")
                ),
                p -> toProjectMap(p)
        )));
    }

    @GetMapping("/projects/{projectId}")
    @Operation(summary = "Get project detail")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProject(
            @PathVariable Long projectId) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        AuditProject project = projectRepository.findById(projectId)
                .filter(p -> isSystem || p.getTenantId() == null
                        || p.getTenantId().equals(ctx.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", projectId));

        Map<String, Object> result = toProjectMap(project);

        // Include tenant access list for SPECIFIC visibility
        if (project.getVisibility() == AuditProject.Visibility.SPECIFIC) {
            result.put("allowedTenantIds",
                    projectTenantAccessRepository.findByProjectId(projectId)
                            .stream().map(a -> a.getTenantId()).toList());
        }

        // Multiple instances per project are valid (annual runs etc.) — show the most recent one.
        projectInstanceRepository.findByOriginalProjectId(projectId).stream()
                .filter(i -> ctx.getTenantId().equals(i.getTenantId()))
                .max(java.util.Comparator.comparing(AuditProjectInstance::getCreatedAt))
                .ifPresent(inst -> {
                    Map<String, Object> snapshot = new LinkedHashMap<>();
                    snapshot.put("id",                  inst.getId());
                    snapshot.put("projectNameSnapshot",  inst.getProjectNameSnapshot());
                    snapshot.put("projectRefSnapshot",   inst.getProjectRefSnapshot());
                    snapshot.put("statusAtSnapshot",     inst.getStatusAtSnapshot());
                    snapshot.put("plannedTemplateCount", inst.getPlannedTemplateCount());
                    snapshot.put("snapshottedAt",        inst.getSnapshottedAt());
                    result.put("instance", snapshot);
                });

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLISH / UNPUBLISH project
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/projects/{projectId}/publish")
    @Operation(summary = "Publish a library project — makes it visible to orgs per visibility rules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publishProject(@PathVariable Long projectId) {
        AuditProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", projectId));
        project.setPublishStatus(AuditProject.PublishStatus.PUBLISHED);
        projectRepository.save(project);
        log.info("[AUDIT-PROJECT] Published | id={}", projectId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", projectId, "publishStatus", "PUBLISHED")));
    }

    @PostMapping("/projects/{projectId}/unpublish")
    @Operation(summary = "Unpublish a library project — hides from org LOOKUP")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unpublishProject(@PathVariable Long projectId) {
        AuditProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", projectId));
        project.setPublishStatus(AuditProject.PublishStatus.DRAFT);
        projectRepository.save(project);
        log.info("[AUDIT-PROJECT] Unpublished | id={}", projectId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", projectId, "publishStatus", "DRAFT")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VISIBILITY + TENANT ACCESS
    // ─────────────────────────────────────────────────────────────────────────

    @PatchMapping("/projects/{projectId}/visibility")
    @Operation(summary = "Set project visibility (GLOBAL/PLATFORM/SPECIFIC)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setVisibility(
            @PathVariable Long projectId,
            @RequestBody Map<String, String> body) {
        AuditProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", projectId));
        AuditProject.Visibility vis = AuditProject.Visibility.valueOf(body.get("visibility").toUpperCase());
        project.setVisibility(vis);
        projectRepository.save(project);
        log.info("[AUDIT-PROJECT] Visibility set | id={} | visibility={}", projectId, vis);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", projectId, "visibility", vis)));
    }

    @GetMapping("/projects/{projectId}/tenant-access")
    @Operation(summary = "List tenants with SPECIFIC access to this project")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listTenantAccess(
            @PathVariable Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(
                projectTenantAccessRepository.findByProjectId(projectId).stream()
                        .map(a -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id",        a.getId());
                            m.put("tenantId",  a.getTenantId());
                            m.put("grantedBy", a.getGrantedBy());
                            m.put("createdAt", a.getCreatedAt());
                            return m;
                        }).toList()));
    }

    @PostMapping("/projects/{projectId}/tenant-access/{tenantId}")
    @Operation(summary = "Grant SPECIFIC access to a tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> grantTenantAccess(
            @PathVariable Long projectId, @PathVariable Long tenantId) {
        var ctx = utilityService.getLoggedInDataContext();
        if (projectTenantAccessRepository.existsByProjectIdAndTenantId(projectId, tenantId)) {
            return ResponseEntity.ok(ApiResponse.success(Map.of("alreadyGranted", true)));
        }
        var access = projectTenantAccessRepository.save(
                AuditProjectTenantAccess.builder()
                        .projectId(projectId).tenantId(tenantId).grantedBy(ctx.getId())
                        .build());
        log.info("[AUDIT-PROJECT] Tenant access granted | projectId={} tenantId={}", projectId, tenantId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", access.getId(), "tenantId", tenantId)));
    }

    @DeleteMapping("/projects/{projectId}/tenant-access/{tenantId}")
    @Operation(summary = "Revoke SPECIFIC access from a tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeTenantAccess(
            @PathVariable Long projectId, @PathVariable Long tenantId) {
        projectTenantAccessRepository.deleteByProjectIdAndTenantId(projectId, tenantId);
        log.info("[AUDIT-PROJECT] Tenant access revoked | projectId={} tenantId={}", projectId, tenantId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("projectId", projectId, "tenantId", tenantId, "revoked", true)));
    }

    /** Shared project → map helper used by listProjects + getProject */
    private Map<String, Object> toProjectMap(AuditProject p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            p.getId());
        m.put("projectRef",    p.getProjectRef());
        m.put("name",          p.getName());
        m.put("description",   p.getDescription());
        m.put("status",        p.getStatus());
        m.put("publishStatus", p.getPublishStatus());
        m.put("visibility",    p.getVisibility());
        m.put("ownerId",       p.getOwnerId());
        m.put("tenantId",      p.getTenantId());
        m.put("global",        p.getTenantId() == null);
        m.put("plannedStart",  p.getPlannedStart());
        m.put("plannedEnd",    p.getPlannedEnd());
        m.put("createdAt",     p.getCreatedAt());
        m.put("workflowInstanceId", p.getWorkflowInstanceId());
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HISTORY — proxies to workflow instance history for this project
    // Frontend HistoryTab calls GET {apiBasePath}/{id}/history
    // Mirrors IssueController.getHistory() — covers all AUDIT_PROJECT workflow
    // instances (normally just one, but future re-runs are supported).
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/projects/{id}/history")
    @Operation(summary = "Full chronological history for a project's workflow")
    public ResponseEntity<ApiResponse<List<WorkflowHistoryResponse>>> getProjectHistory(
            @PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        List<com.kashi.grc.workflow.domain.WorkflowInstance> instances =
                instanceRepository.findByTenantIdAndEntityTypeAndEntityId(
                        ctx.getTenantId(), "AUDIT_PROJECT", id);
        if (instances.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<WorkflowHistoryResponse> allHistory = new ArrayList<>();
        instances.stream()
                .sorted(java.util.Comparator.comparing(
                        com.kashi.grc.workflow.domain.WorkflowInstance::getId))
                .forEach(inst -> allHistory.addAll(
                        workflowEngineService.getFullHistory(inst.getId())));
        return ResponseEntity.ok(ApiResponse.success(allHistory));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PROJECT INSTANCES — running instances of a library AuditProject.
    //
    // Mirrors the AuditTemplate (library) ↔ AuditEngagement (instance) split:
    //   AuditProject         (library — name, owner, planned templates; lives
    //                          in Audit Library, unaffected by this section)
    //   AuditProjectInstance (instance — THIS is what /module/audit_project
    //                          lists/details. One row per "Create" below.)
    //
    // POST creates the instance AND, in the same call, cascades all planned
    // templates into AuditEngagement rows (projectInstanceId = this instance,
    // NO workflow-14) and starts ONE workflow-16 instance
    // (entityType=AUDIT_PROJECT, entityId=instance.id).
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/project-instances")
    @Operation(summary = "List running project instances for this tenant")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listProjectInstances() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        List<AuditProjectInstance> instances = projectInstanceRepository.findByTenantIdOrderByIdDesc(tenantId);

        // Bulk-load workflow statuses to avoid N+1 per instance
        Set<Long> wfIds = instances.stream()
                .map(AuditProjectInstance::getWorkflowInstanceId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<Long, com.kashi.grc.workflow.domain.WorkflowInstance> wfMap = instanceRepository.findAllById(wfIds)
                .stream().collect(java.util.stream.Collectors.toMap(
                        com.kashi.grc.workflow.domain.WorkflowInstance::getId, wi -> wi));

        return ResponseEntity.ok(ApiResponse.success(
                instances.stream()
                        .map(inst -> toProjectInstanceMap(inst, wfMap))
                        .toList()));
    }

    @GetMapping("/project-instances/{id}")
    @Operation(summary = "Get a running project instance — includes workflowInstanceId")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProjectInstance(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        AuditProjectInstance inst = projectInstanceRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProjectInstance", id));

        return ResponseEntity.ok(ApiResponse.success(toProjectInstanceMap(inst)));
    }

    @GetMapping("/project-instances/{id}/history")
    @Operation(summary = "Full chronological history for a project instance's workflow")
    public ResponseEntity<ApiResponse<List<WorkflowHistoryResponse>>> getProjectInstanceHistory(
            @PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        List<com.kashi.grc.workflow.domain.WorkflowInstance> instances =
                instanceRepository.findByTenantIdAndEntityTypeAndEntityId(
                        ctx.getTenantId(), "AUDIT_PROJECT", id);
        if (instances.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<WorkflowHistoryResponse> allHistory = new ArrayList<>();
        instances.stream()
                .sorted(java.util.Comparator.comparing(
                        com.kashi.grc.workflow.domain.WorkflowInstance::getId))
                .forEach(inst -> allHistory.addAll(
                        workflowEngineService.getFullHistory(inst.getId())));
        return ResponseEntity.ok(ApiResponse.success(allHistory));
    }

    @GetMapping("/project-instances/{id}/report-data")
    @Operation(summary = "Aggregated programme report data — control stats + findings across all engagements under this project instance")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProjectInstanceReportData(
            @PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        AuditProjectInstance inst = projectInstanceRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProjectInstance", id));

        List<AuditEngagement> engagements = engagementRepository.findByProjectInstanceId(id);

        long programTotalControls  = 0;
        long programEffective      = 0;
        long programPartiallyEff   = 0;
        long programIneffective    = 0;
        long programNotTested      = 0;
        long programTotalFindings  = 0;
        long programCriticalFind   = 0;
        long programHighFind       = 0;
        long programMediumFind     = 0;
        long programLowFind        = 0;
        long programOpenFind       = 0;

        List<Map<String, Object>> engBreakdown = new ArrayList<>();

        for (AuditEngagement eng : engagements) {
            List<AuditControlInstance> controls = controlInstanceRepository.findByEngagementId(eng.getId());

            long total       = controls.size();
            long effective   = controls.stream().filter(c -> AuditControlInstance.TestResult.EFFECTIVE           == c.getTestResult()).count();
            long partiallyEff= controls.stream().filter(c -> AuditControlInstance.TestResult.PARTIALLY_EFFECTIVE == c.getTestResult()).count();
            long ineffective = controls.stream().filter(c -> AuditControlInstance.TestResult.INEFFECTIVE         == c.getTestResult()).count();
            long notTested   = controls.stream().filter(c -> c.getTestResult() == null
                    || AuditControlInstance.TestResult.NOT_TESTED == c.getTestResult()).count();
            double passRate  = total > 0 ? Math.round((effective * 10000.0) / total) / 100.0 : 0.0;

            List<AuditFinding> findings = findingRepository.findByEngagementIdAndTenantId(eng.getId(), tenantId);
            long critical = findings.stream().filter(f -> AuditFinding.Severity.CRITICAL == f.getSeverity()).count();
            long high     = findings.stream().filter(f -> AuditFinding.Severity.HIGH     == f.getSeverity()).count();
            long medium   = findings.stream().filter(f -> AuditFinding.Severity.MEDIUM   == f.getSeverity()).count();
            long low      = findings.stream().filter(f -> AuditFinding.Severity.LOW      == f.getSeverity()).count();
            long open     = findings.stream().filter(f ->
                    AuditFinding.Status.OPEN == f.getStatus() || AuditFinding.Status.IN_REMEDIATION == f.getStatus()).count();

            // Per-control detail for the controls table
            List<Map<String, Object>> controlRows = controls.stream().map(c -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",             c.getId());
                row.put("controlRef",     c.getControlCodeSnapshot());
                row.put("name",           c.getControlNameSnapshot());
                row.put("frameworkRef",   c.getFrameworkRefSnapshot());
                row.put("testResult",     c.getTestResult() != null ? c.getTestResult().name() : "NOT_TESTED");
                row.put("testNotes",      c.getTestNotes());
                row.put("sectionPath",    c.getSectionPath());
                return row;
            }).toList();

            // Per-finding detail
            List<Map<String, Object>> findingRows = findings.stream().map(f -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",          f.getId());
                row.put("title",       f.getTitle());
                row.put("severity",    f.getSeverity() != null ? f.getSeverity().name() : null);
                row.put("status",      f.getStatus() != null ? f.getStatus().name() : null);
                row.put("description", f.getDescription());
                row.put("remediation", f.getRemediationPlan());
                return row;
            }).toList();

            programTotalControls += total;
            programEffective     += effective;
            programPartiallyEff  += partiallyEff;
            programIneffective   += ineffective;
            programNotTested     += notTested;
            programTotalFindings += findings.size();
            programCriticalFind  += critical;
            programHighFind      += high;
            programMediumFind    += medium;
            programLowFind       += low;
            programOpenFind      += open;

            Map<String, Object> engRow = new LinkedHashMap<>();
            engRow.put("engagementId",      eng.getId());
            engRow.put("engagementRef",     eng.getEngagementRef());
            engRow.put("name",              eng.getName());
            engRow.put("frameworkRef",      eng.getFrameworkRef());
            engRow.put("auditType",         eng.getAuditType() != null ? eng.getAuditType().name() : null);
            engRow.put("status",            eng.getStatus() != null ? eng.getStatus().name() : null);
            engRow.put("plannedStart",      eng.getPlannedStart());
            engRow.put("plannedEnd",        eng.getPlannedEnd());
            engRow.put("leadAuditorId",     eng.getLeadAuditorId());
            engRow.put("totalControls",     total);
            engRow.put("effective",         effective);
            engRow.put("partiallyEffective",partiallyEff);
            engRow.put("ineffective",       ineffective);
            engRow.put("notTested",         notTested);
            engRow.put("passRatePct",       passRate);
            engRow.put("totalFindings",     findings.size());
            engRow.put("criticalFindings",  critical);
            engRow.put("highFindings",      high);
            engRow.put("mediumFindings",    medium);
            engRow.put("lowFindings",       low);
            engRow.put("openFindings",      open);
            engRow.put("controls",          controlRows);
            engRow.put("findings",          findingRows);
            engBreakdown.add(engRow);
        }

        double programPassRate = programTotalControls > 0
                ? Math.round((programEffective * 10000.0) / programTotalControls) / 100.0 : 0.0;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("instanceId",           inst.getId());
        report.put("instanceRef",          inst.getInstanceRef());
        report.put("projectRef",           inst.getProjectRefSnapshot());
        report.put("projectName",          inst.getProjectNameSnapshot());
        report.put("description",          inst.getDescriptionSnapshot());
        report.put("status",               inst.getStatus());
        report.put("ownerId",              inst.getOwnerIdSnapshot());
        report.put("plannedStart",         inst.getPlannedStartSnapshot());
        report.put("plannedEnd",           inst.getPlannedEndSnapshot());
        report.put("workflowInstanceId",   inst.getWorkflowInstanceId());
        report.put("engagementCount",      engagements.size());
        report.put("totalControls",        programTotalControls);
        report.put("effectiveControls",    programEffective);
        report.put("partiallyEffective",   programPartiallyEff);
        report.put("ineffectiveControls",  programIneffective);
        report.put("notTestedControls",    programNotTested);
        report.put("passRatePct",          programPassRate);
        report.put("totalFindings",        programTotalFindings);
        report.put("criticalFindings",     programCriticalFind);
        report.put("highFindings",         programHighFind);
        report.put("mediumFindings",       programMediumFind);
        report.put("lowFindings",          programLowFind);
        report.put("openFindings",         programOpenFind);
        report.put("engagements",          engBreakdown);
        report.put("generatedAt",          java.time.LocalDateTime.now().toString());

        return ResponseEntity.ok(ApiResponse.success(report));
    }



    // ─────────────────────────────────────────────────────────────────────────
    // CREATE / START — cascades ALL planned templates of the selected library
    // AuditProject into engagement instances (sections/controls/tests/policies,
    // same as templates/{id}/start) WITHOUT starting per-engagement workflow-14
    // instances, then starts ONE workflow-16 instance
    // (entityType=AUDIT_PROJECT, entityId=instance.id) that governs the entire
    // programme. Steps 2/3/5/7 fan out per-engagement via compound tasks
    // (see AuditProjectEngagementItemRegistrar).
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/project-instances")
    @Operation(summary = "Create a project instance from a library AuditProject — cascades engagements and starts workflow 16 for the whole programme")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createProjectInstance(
            @RequestBody Map<String, Object> body) {

        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        if (body.get("originalProjectId") == null) {
            throw new BusinessException("MISSING_PROJECT_ID", "originalProjectId is required");
        }
        Long originalProjectId = Long.parseLong(body.get("originalProjectId").toString());

        AuditProject project = projectRepository.findById(originalProjectId)
                .filter(p -> utilityService.isSystemUser() || p.getTenantId() == null
                        || p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", originalProjectId));

        // NOTE: multiple instances of the same library project are allowed —
        // e.g. "SOC2 Programme" run for 2026, then again for 2027. Each call
        // creates an independent AuditProjectInstance + cascaded engagements +
        // its own workflow-16 instance.

        // ── Org-supplied instance parameters ────────────────────────────────
        // The library AuditProject may be GLOBAL (tenantId=null, created by
        // Platform Admin) — its ownerId/plannedStart/plannedEnd are generic
        // template values, NOT meaningful for any specific org's run. Each
        // org instantiating it supplies their OWN owner + dates here, rather
        // than snapshotting the library project's (possibly empty) values.
        if (body.get("ownerId") == null) {
            throw new BusinessException("MISSING_OWNER", "ownerId is required");
        }
        if (body.get("plannedStart") == null || body.get("plannedEnd") == null) {
            throw new BusinessException("MISSING_DATES", "plannedStart and plannedEnd are required");
        }
        Long instanceOwnerId = Long.parseLong(body.get("ownerId").toString());
        java.time.LocalDate instancePlannedStart = java.time.LocalDate.parse(body.get("plannedStart").toString());
        java.time.LocalDate instancePlannedEnd   = java.time.LocalDate.parse(body.get("plannedEnd").toString());

        // ── VALIDATE ALL INPUTS BEFORE ANY DB WRITES ─────────────────────────
        // Critical: validate workflow + templates BEFORE saving the instance row,
        // so a failed validation doesn't leave an orphaned AuditProjectInstance.

        List<AuditProjectTemplate> planned = projectTemplateRepository
                .findByProjectIdOrderByOrderNoAsc(originalProjectId);

        if (planned.isEmpty()) {
            throw new BusinessException("NO_TEMPLATES_PLANNED",
                    "Add at least one template to the project plan (in Audit Library) before starting it");
        }

        // Resolve workflow BEFORE any saves — fail fast if no active AUDIT_PROJECT workflow
        Long workflowId = null;
        if (body.get("workflowId") != null) {
            workflowId = Long.parseLong(body.get("workflowId").toString());
        }
        if (workflowId == null) {
            workflowId = workflowRepositoryFindDefault();
        }
        final Long resolvedWorkflowId = workflowId;

        // Generate unique instance ref: PROJ-2026-0001-RUN-N
        long runSeq = projectInstanceRepository.countByOriginalProjectIdAndTenantId(originalProjectId, tenantId) + 1;
        String instanceRef = project.getProjectRef() + "-RUN-" + runSeq;

        // ── 1. Create the project instance (snapshot) ──────────────────────────
        AuditProjectInstance inst = projectInstanceRepository.save(
                AuditProjectInstance.builder()
                        .originalProjectId(project.getId())
                        .tenantId(tenantId)
                        .instanceRef(instanceRef)
                        .projectNameSnapshot(project.getName())
                        .projectRefSnapshot(project.getProjectRef())
                        .descriptionSnapshot(project.getDescription())
                        .ownerIdSnapshot(instanceOwnerId)
                        .plannedStartSnapshot(instancePlannedStart)
                        .plannedEndSnapshot(instancePlannedEnd)
                        .statusAtSnapshot(project.getStatus() != null ? project.getStatus().name() : "PLANNING")
                        .plannedTemplateCount(planned.size())
                        .snapshottedAt(LocalDateTime.now())
                        .snapshottedBy(ctx.getId())
                        .status("PLANNING")
                        .build());

        // ── 2. Cascade-create an engagement instance per planned template ──────
        // Mirrors startEngagementFromPlan() but with startWorkflow=false — no
        // workflow-14 instance per engagement, since workflow 16 governs all.
        List<Long> createdEngagementIds = new ArrayList<>();
        for (AuditProjectTemplate pt : planned) {
            AuditTemplate template = templateRepository.findById(pt.getTemplateId())
                    .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", pt.getTemplateId()));

            AuditEngagementRequest engReq = new AuditEngagementRequest();
            engReq.setProjectId(originalProjectId);
            engReq.setProjectInstanceId(inst.getId()); // pre-pass instance id — bypasses findByOriginalProjectId in service
            engReq.setTemplateId(pt.getTemplateId());
            engReq.setName(project.getName() + " — " + template.getName());
            engReq.setFrameworkRef(template.getFrameworkRef());
            engReq.setAuditType(template.getAuditType());
            engReq.setOwnerId(instanceOwnerId);
            engReq.setPlannedStart(instancePlannedStart);
            engReq.setPlannedEnd(instancePlannedEnd);

            AuditEngagementResponse engagement = service.create(engReq, ctx.getId(), tenantId, false);

            // Point the engagement at THIS instance — registrar and tabs query by projectInstanceId
            engagementRepository.findById(engagement.getId()).ifPresent(e -> {
                e.setProjectInstanceId(inst.getId());
                engagementRepository.save(e);
            });

            pt.setEngagementId(engagement.getId());
            projectTemplateRepository.save(pt);
            createdEngagementIds.add(engagement.getId());

            log.info("[AUDIT-PROJECT-INSTANCE] Cascaded engagement | instanceId={} templateId={} → engagementId={}",
                    inst.getId(), pt.getTemplateId(), engagement.getId());
        }

        // ── 3. Start workflow ONCE for the whole project instance ──────────────
        StartWorkflowRequest wfReq = new StartWorkflowRequest();
        wfReq.setWorkflowId(resolvedWorkflowId);
        wfReq.setEntityType("AUDIT_PROJECT");
        wfReq.setEntityId(inst.getId());
        wfReq.setPriority("MEDIUM");
        if (inst.getPlannedEndSnapshot() != null) {
            wfReq.setDueDate(inst.getPlannedEndSnapshot().atStartOfDay());
        }

        var wf = workflowEngineService.startWorkflow(wfReq, tenantId, ctx.getId());
        inst.setWorkflowInstanceId(wf.getId());
        inst.setStatus("IN_PROGRESS");
        projectInstanceRepository.save(inst);

        log.info("[AUDIT-PROJECT-INSTANCE] Started | instanceId={} | originalProjectId={} | engagements={} | workflowInstanceId={}",
                inst.getId(), originalProjectId, createdEngagementIds, wf.getId());

        Map<String, Object> result = toProjectInstanceMap(inst);
        result.put("engagementIds", createdEngagementIds);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    /**
     * Resolves the default AUDIT_PROJECT workflow ID by entityType + active flag,
     * mirroring the "AUDIT_ENGAGEMENT_<type>" convention in
     * AuditEngagementService.startWorkflowIfConfigured() — avoids hardcoding id=16.
     */
    private Long workflowRepositoryFindDefault() {
        return workflowRepository
                .findByTenantIdIsNullAndEntityTypeAndIsActiveTrue("AUDIT_PROJECT")
                .stream().findFirst()
                .map(com.kashi.grc.workflow.domain.Workflow::getId)
                .orElseThrow(() -> new BusinessException("NO_PROJECT_WORKFLOW",
                        "No active workflow with entityType=AUDIT_PROJECT found. " +
                                "Either pass workflowId explicitly or activate workflow id=16."));
    }

    private Map<String, Object> toProjectInstanceMap(AuditProjectInstance inst) {
        // Single-fetch fallback for getProjectInstance (single entity endpoint)
        Map<Long, com.kashi.grc.workflow.domain.WorkflowInstance> wfMap = new java.util.HashMap<>();
        if (inst.getWorkflowInstanceId() != null) {
            instanceRepository.findById(inst.getWorkflowInstanceId())
                    .ifPresent(wi -> wfMap.put(wi.getId(), wi));
        }
        return toProjectInstanceMap(inst, wfMap);
    }

    private Map<String, Object> toProjectInstanceMap(
            AuditProjectInstance inst,
            Map<Long, com.kashi.grc.workflow.domain.WorkflowInstance> wfMap) {

        // Derive status from the actual workflow instance — inst.status is hardcoded
        // to IN_PROGRESS at creation and never updated automatically.
        String derivedStatus = inst.getStatus() != null ? inst.getStatus() : "IN_PROGRESS";
        if (inst.getWorkflowInstanceId() != null) {
            var wi = wfMap.get(inst.getWorkflowInstanceId());
            if (wi != null) {
                derivedStatus = switch (wi.getStatus()) {
                    case COMPLETED -> "COMPLETED";
                    case ON_HOLD   -> "ON_HOLD";
                    case CANCELLED -> "CANCELLED";
                    case PENDING   -> "PENDING";
                    default        -> derivedStatus;
                };
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                  inst.getId());
        m.put("instanceRef",         inst.getInstanceRef());
        m.put("projectRef",          inst.getProjectRefSnapshot());
        m.put("name",                inst.getProjectNameSnapshot());
        m.put("description",         inst.getDescriptionSnapshot());
        m.put("status",              derivedStatus);
        m.put("ownerId",             inst.getOwnerIdSnapshot());
        m.put("plannedStart",        inst.getPlannedStartSnapshot());
        m.put("plannedEnd",          inst.getPlannedEndSnapshot());
        m.put("originalProjectId",   inst.getOriginalProjectId());
        m.put("plannedTemplateCount",inst.getPlannedTemplateCount());
        m.put("workflowInstanceId",  inst.getWorkflowInstanceId());
        m.put("createdAt",           inst.getCreatedAt());
        // ── Step 11: Cross-Framework Consolidation ────────────────────────────
        m.put("crossFrameworkNotes", inst.getCrossFrameworkNotes());
        m.put("programmeRisk",       inst.getProgrammeRisk());
        // ── Step 12: Management Response ──────────────────────────────────────
        m.put("managementResponse",   inst.getManagementResponse());
        m.put("acceptanceOfFindings", inst.getAcceptanceOfFindings());
        m.put("correctiveActions",    inst.getCorrectiveActions());
        m.put("committedClosureDate", inst.getCommittedClosureDate());
        // ── Step 13: Executive Sign-off ───────────────────────────────────────
        m.put("executiveSignOff",    inst.getExecutiveSignOff());
        m.put("programmeOutcome",    inst.getProgrammeOutcome());
        m.put("closureStatement",    inst.getClosureStatement());
        m.put("nextAuditDue",        inst.getNextAuditDue());
        m.put("signedOffBy",         inst.getSignedOffBy());
        m.put("signedOffAt",         inst.getSignedOffAt());
        return m;
    }

    //
    // Works for both org-scoped and global projects — org can adopt a global
    // project by adding templates to it via their project detail page.
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/projects/{projectId}/templates")
    @Operation(summary = "List templates planned for a project — library references, not instances")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listProjectTemplates(
            @PathVariable Long projectId) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        projectRepository.findById(projectId)
                .filter(p -> isSystem || p.getTenantId() == null
                        || p.getTenantId().equals(ctx.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", projectId));

        List<Map<String, Object>> result =
                projectTemplateRepository.findByProjectIdOrderByOrderNoAsc(projectId)
                        .stream().map(pt -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("id",           pt.getId());
                            row.put("projectId",    pt.getProjectId());
                            row.put("templateId",   pt.getTemplateId());
                            row.put("orderNo",      pt.getOrderNo());
                            row.put("note",         pt.getNote());
                            row.put("engagementId", pt.getEngagementId());
                            row.put("started",      pt.getEngagementId() != null);
                            templateRepository.findById(pt.getTemplateId()).ifPresent(t -> {
                                row.put("templateName",      t.getName());
                                row.put("templateStatus",    t.getStatus());
                                row.put("templateFramework", t.getFrameworkRef());
                                row.put("templateAuditType", t.getAuditType());
                                row.put("templateVersion",   t.getVersion());
                            });
                            return row;
                        }).toList();

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/projects/{projectId}/templates/{templateId}")
    @Operation(summary = "Add a PUBLISHED template to a project plan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addTemplateToProject(
            @PathVariable Long projectId,
            @PathVariable Long templateId,
            @RequestParam(required = false) String  note,
            @RequestParam(required = false) Integer orderNo) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        projectRepository.findById(projectId)
                .filter(p -> isSystem || p.getTenantId() == null
                        || p.getTenantId().equals(ctx.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", projectId));

        AuditTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("AuditTemplate", templateId));

        if (!"PUBLISHED".equals(template.getStatus()))
            throw new BusinessException("TEMPLATE_NOT_PUBLISHED",
                    "Only published templates can be added to a project plan");

        if (projectTemplateRepository.existsByProjectIdAndTemplateId(projectId, templateId))
            throw new BusinessException("TEMPLATE_ALREADY_PLANNED",
                    "Template is already in this project's plan");

        int nextOrder = orderNo != null ? orderNo :
                projectTemplateRepository.findByProjectIdOrderByOrderNoAsc(projectId).size();

        AuditProjectTemplate pt = projectTemplateRepository.save(
                AuditProjectTemplate.builder()
                        .projectId(projectId)
                        .templateId(templateId)
                        .orderNo(nextOrder)
                        .note(note)
                        .build()
        );

        log.info("[AUDIT] Template → project plan | projectId={} templateId={}", projectId, templateId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "id",         pt.getId(),
                "projectId",  projectId,
                "templateId", templateId,
                "orderNo",    nextOrder,
                "note",       note != null ? note : "",
                "started",    false
        )));
    }

    @DeleteMapping("/projects/{projectId}/templates/{templateId}")
    @Operation(summary = "Remove a planned template — only if not yet started")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeTemplateFromProject(
            @PathVariable Long projectId,
            @PathVariable Long templateId) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        projectRepository.findById(projectId)
                .filter(p -> isSystem || p.getTenantId() == null
                        || p.getTenantId().equals(ctx.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", projectId));

        AuditProjectTemplate pt = projectTemplateRepository
                .findByProjectIdAndTemplateId(projectId, templateId)
                .orElseThrow(() -> new BusinessException("PLAN_NOT_FOUND",
                        "Template is not in this project's plan"));

        if (pt.getEngagementId() != null)
            throw new BusinessException("PLAN_ALREADY_STARTED",
                    "Cannot remove a template plan that has already been started. " +
                            "Engagement id=" + pt.getEngagementId() + " continues independently.");

        projectTemplateRepository.deleteByProjectIdAndTemplateId(projectId, templateId);
        log.info("[AUDIT] Template ← project plan removed | projectId={} templateId={}",
                projectId, templateId);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("projectId", projectId, "templateId", templateId, "removed", true)));
    }

    @PostMapping("/projects/{projectId}/templates/{templateId}/start")
    @Operation(summary = "Start engagement from a planned template — snapshots project + template into isolated instances")
    public ResponseEntity<ApiResponse<AuditEngagementResponse>> startEngagementFromPlan(
            @PathVariable Long projectId,
            @PathVariable Long templateId,
            @Valid @RequestBody AuditEngagementRequest req) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        projectRepository.findById(projectId)
                .filter(p -> isSystem || p.getTenantId() == null
                        || p.getTenantId().equals(ctx.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", projectId));

        AuditProjectTemplate pt = projectTemplateRepository
                .findByProjectIdAndTemplateId(projectId, templateId)
                .orElseThrow(() -> new BusinessException("PLAN_NOT_FOUND",
                        "Template " + templateId + " is not in project " + projectId + "'s plan"));

        if (pt.getEngagementId() != null)
            throw new BusinessException("PLAN_ALREADY_STARTED",
                    "This plan has already been started. Engagement id=" + pt.getEngagementId());

        // Lock projectId and templateId — cannot be overridden by request body
        req.setProjectId(projectId);
        req.setTemplateId(templateId);

        // create() → ensureProjectInstance() (snapshot project once)
        //           + snapshotTemplate() (snapshot template per engagement)
        //           → 100% isolated instances
        AuditEngagementResponse engagement = service.create(req, ctx.getId(), ctx.getTenantId());

        // Mark plan as started — prevents double-start
        pt.setEngagementId(engagement.getId());
        projectTemplateRepository.save(pt);

        log.info("[AUDIT] Plan started | projectId={} templateId={} → engagementId={}",
                projectId, templateId, engagement.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(engagement));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ENGAGEMENTS
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/engagements")
    @Operation(summary = "Create engagement directly — use /projects/{id}/templates/{id}/start for planned flow")
    public ResponseEntity<ApiResponse<AuditEngagementResponse>> createEngagement(
            @Valid @RequestBody AuditEngagementRequest req) {
        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(req, ctx.getId(), ctx.getTenantId())));
    }

    @GetMapping("/engagements")
    @Operation(summary = "List engagements — filterable by projectId, status, auditType")
    public ResponseEntity<?> listEngagements(
            @RequestParam Map<String, String> allParams) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // ── Fast path: projectInstanceId filter → return full entity via repository ──
        // The ProjectEngagementsTab needs ALL entity fields (leadAuditorId, auditeeAssignedUserId
        // etc.) to drive assignment UI. The generic mapper below only includes a curated
        // subset. Using the repository directly avoids hardcoding field lists here.
        if (allParams.containsKey("projectInstanceId")) {
            Long projectInstanceId = Long.parseLong(allParams.get("projectInstanceId"));
            List<AuditEngagement> engs = engagementRepository.findByProjectInstanceId(projectInstanceId)
                    .stream()
                    .filter(e -> e.getTenantId().equals(tenantId))
                    .toList();
            var pd = new com.kashi.grc.common.dto.PageDetails();
            pd.setTake(engs.size() > 0 ? engs.size() : 100);
            pd.setSkip(0L);
            return ResponseEntity.ok(ApiResponse.success(
                    new com.kashi.grc.common.dto.PaginatedResponse<>(engs, engs.size(), pd)));
        }

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AuditEngagement.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    var preds = new ArrayList<jakarta.persistence.criteria.Predicate>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    if (allParams.containsKey("projectId"))
                        preds.add(cb.equal(root.get("projectId"),
                                Long.parseLong(allParams.get("projectId"))));
                    if (allParams.containsKey("projectInstanceId"))
                        preds.add(cb.equal(root.get("projectInstanceId"),
                                Long.parseLong(allParams.get("projectInstanceId"))));
                    else if (!allParams.containsKey("projectId"))
                        // Default list (no project filter): exclude project-governed engagements
                        // so the SOC2 list only shows standalone engagements, not ones created
                        // under an Audit Project Instance (which have their own Engagements tab).
                        preds.add(cb.isNull(root.get("projectInstanceId")));
                    if (allParams.containsKey("status"))
                        preds.add(cb.equal(root.get("status"),
                                AuditEngagement.Status.valueOf(allParams.get("status").toUpperCase())));
                    if (allParams.containsKey("auditType"))
                        preds.add(cb.equal(root.get("auditType"),
                                AuditTemplate.AuditType.valueOf(allParams.get("auditType").toUpperCase())));
                    return preds;
                },
                (cb, root) -> Map.of(
                        "name",        root.get("name"),
                        "status",      root.get("status"),
                        "auditType",   root.get("auditType"),
                        "frameworkRef",root.get("frameworkRef"),
                        "createdAt",   root.get("createdAt")
                ),
                e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",                 e.getId());
                    m.put("engagementRef",      e.getEngagementRef());
                    m.put("projectId",          e.getProjectId());
                    m.put("projectInstanceId",  e.getProjectInstanceId());
                    m.put("name",               e.getName());
                    m.put("auditType",          e.getAuditType());
                    m.put("status",             e.getStatus());
                    m.put("frameworkRef",       e.getFrameworkRef() != null ? e.getFrameworkRef() : "");
                    m.put("totalControls",      e.getTotalControls());
                    m.put("testedControls",     e.getTestedControls());
                    m.put("openFindingCount",   e.getOpenFindingCount());
                    m.put("workflowInstanceId", e.getWorkflowInstanceId());
                    m.put("createdAt",          e.getCreatedAt());
                    m.put("listScreenKey",      e.getListScreenKey());
                    return m;
                }
        )));
    }

    @PatchMapping("/engagements/{id}")
    @Operation(summary = "Partial update — inline field edits from UniversalModulePage overview tab")
    public ResponseEntity<ApiResponse<Void>> patchEngagement(
            @PathVariable Long id,
            @RequestBody Map<String, Object> fields) {

        AuditEngagement e = engagementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", id));

        // Only allow patching these safe mutable fields — ignore anything else
        if (fields.containsKey("name"))         e.setName((String) fields.get("name"));
        if (fields.containsKey("description"))  e.setDescription((String) fields.get("description"));
        if (fields.containsKey("frameworkRef")) e.setFrameworkRef((String) fields.get("frameworkRef"));
        if (fields.containsKey("leadAuditorId"))
            e.setLeadAuditorId(fields.get("leadAuditorId") != null
                    ? Long.parseLong(fields.get("leadAuditorId").toString()) : null);
        if (fields.containsKey("ownerId"))
            e.setOwnerId(fields.get("ownerId") != null
                    ? Long.parseLong(fields.get("ownerId").toString()) : null);
        if (fields.containsKey("plannedStart"))
            e.setPlannedStart(fields.get("plannedStart") != null
                    ? java.time.LocalDate.parse(fields.get("plannedStart").toString()).atStartOfDay() : null);
        if (fields.containsKey("plannedEnd"))
            e.setPlannedEnd(fields.get("plannedEnd") != null
                    ? java.time.LocalDate.parse(fields.get("plannedEnd").toString()).atStartOfDay() : null);

        engagementRepository.save(e);
        log.info("[AUDIT-ENG] Patched | id={} | fields={}", id, fields.keySet());

        // When leadAuditorId is set (Step 2 — Assign Lead Auditors), fire the
        // ENGAGEMENTS_LEAD_ASSIGNED section item completion so the per-engagement
        // item gate closes. Once all engagements in the project have a lead auditor
        // assigned, done==total and the step auto-approves.
        if (fields.containsKey("leadAuditorId")) {
            if (e.getLeadAuditorId() != null) {
                Long performedBy = utilityService.getLoggedInDataContext().getId();
                service.fireProjectSectionEvent(
                        e.getProjectInstanceId(),
                        "ENGAGEMENTS_LEAD_ASSIGNED",
                        id,
                        performedBy
                );
            } else {
                // Cleared — re-open the item so gate doesn't auto-approve with null lead auditor
                service.uncompleteEngagementItem(e.getProjectInstanceId(), "ENGAGEMENTS_LEAD_ASSIGNED", id);
            }
        }

        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/engagements/{id}")
    @Operation(summary = "Get engagement detail — flat response for UniversalModulePage compatibility")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getEngagement(@PathVariable Long id) {
        AuditEngagement e = engagementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", id));

        // Flat map — same pattern as buildTemplateMap() in AuditLibraryController.
        // UniversalModulePage reads entity.id, entity.name, entity.status etc. directly,
        // so all fields must be at the top level (not nested under an "engagement" key).
        // Sections and controls are intentionally omitted — they have their own endpoints
        // (/engagements/{id}/sections and /engagements/{id}/controls) called by the tab
        // components (EngagementSectionsTab, EngagementControlsTab) directly.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id",                 e.getId());
        result.put("engagementRef",      e.getEngagementRef());
        result.put("name",               e.getName());
        result.put("description",        e.getDescription());
        result.put("status",             e.getStatus());
        result.put("auditType",          e.getAuditType());
        result.put("frameworkRef",       e.getFrameworkRef());
        result.put("templateId",         e.getTemplateId());
        result.put("projectId",          e.getProjectId());
        result.put("leadAuditorId",      e.getLeadAuditorId());
        result.put("ownerId",            e.getOwnerId());
        result.put("plannedStart",       e.getPlannedStart());
        result.put("plannedEnd",         e.getPlannedEnd());
        result.put("actualStart",        e.getActualStart());
        result.put("completedAt",        e.getCompletedAt());
        result.put("totalControls",      e.getTotalControls());
        result.put("testedControls",     e.getTestedControls());
        result.put("openFindingCount",   e.getOpenFindingCount());
        result.put("workflowInstanceId", e.getWorkflowInstanceId());
        result.put("projectInstanceId",  e.getProjectInstanceId());
        result.put("tenantId",           e.getTenantId());
        result.put("createdAt",          e.getCreatedAt());
        // ── Step 9: Draft Report Review fields ────────────────────────────────
        result.put("auditOpinion",       e.getAuditOpinion());
        result.put("overallRating",      e.getOverallRating());
        result.put("executiveSummary",   e.getExecutiveSummary());
        result.put("reviewComments",     e.getReviewComments());
        result.put("scopeLimitations",   e.getScopeLimitations());
        result.put("reportVersion",      e.getReportVersion());
        result.put("reviewedAt",         e.getReviewedAt());
        result.put("reviewedBy",         e.getReviewedBy());

        // Attach frozen project snapshot for context — same as before
        if (e.getProjectInstanceId() != null) {
            projectInstanceRepository.findById(e.getProjectInstanceId()).ifPresent(inst -> {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("id",                  inst.getId());
                snapshot.put("projectNameSnapshot",  inst.getProjectNameSnapshot());
                snapshot.put("projectRefSnapshot",   inst.getProjectRefSnapshot());
                snapshot.put("snapshottedAt",        inst.getSnapshottedAt());
                result.put("projectSnapshot", snapshot);
                // Flat alias for generic breadcrumb parentNameField resolution
                result.put("projectName", inst.getProjectNameSnapshot());
            });
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HISTORY — proxies to workflow instance history for this engagement
    // Frontend HistoryTab calls GET {apiBasePath}/{id}/history
    // Mirrors IssueController.getHistory().
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/engagements/{id}/history")
    @Operation(summary = "Full chronological history for an engagement's workflow")
    public ResponseEntity<ApiResponse<List<WorkflowHistoryResponse>>> getEngagementHistory(
            @PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        List<com.kashi.grc.workflow.domain.WorkflowInstance> instances =
                instanceRepository.findByTenantIdAndEntityTypeAndEntityId(
                        ctx.getTenantId(), "AUDIT_ENGAGEMENT", id);
        if (instances.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<WorkflowHistoryResponse> allHistory = new ArrayList<>();
        instances.stream()
                .sorted(java.util.Comparator.comparing(
                        com.kashi.grc.workflow.domain.WorkflowInstance::getId))
                .forEach(inst -> allHistory.addAll(
                        workflowEngineService.getFullHistory(inst.getId())));
        return ResponseEntity.ok(ApiResponse.success(allHistory));
    }

    @GetMapping("/engagements/{id}/stats")
    @Operation(summary = "Engagement progress and finding stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats(@PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.getEngagementStats(id, ctx.getTenantId())));
    }

    @PatchMapping("/engagements/{id}/status")
    @Operation(summary = "Update engagement status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateStatus(
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        AuditEngagement e = engagementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", id));
        e.setStatus(AuditEngagement.Status.valueOf(body.get("status").toUpperCase()));
        engagementRepository.save(e);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("status", e.getStatus());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SECTIONS (instance layer)
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/engagements/{id}/sections")
    @Operation(summary = "List section instances for an engagement, ordered by tree path")
    public ResponseEntity<ApiResponse<List<AuditSectionInstance>>> listSections(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                sectionInstanceRepository.findByEngagementIdOrderByPathAscOrderNoAsc(id)));
    }

    /**
     * GET /v1/audit/engagements/{id}/assignable-auditees
     * Returns AUDITEE_CONTRIBUTOR users for the tenant — accessible to section auditee owners
     * who need to sub-assign individual controls to peers (no USER_VIEW permission required).
     */
    @GetMapping("/engagements/{id}/assignable-auditees")
    @Operation(summary = "List users assignable as control auditees for this engagement")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAssignableAuditees(
            @PathVariable Long id) {
        var ctx = utilityService.getLoggedInDataContext();
        // Return all AUDITEE-side users for the tenant — no USER_VIEW permission needed.
        // Uses the same role-side lookup as eligible-users but scoped to AUDITEE side.
        List<Long> roleIds = roleRepository
                .findAllForTenantBySide(ctx.getTenantId(),
                        com.kashi.grc.usermanagement.domain.RoleSide.AUDITEE)
                .stream().map(r -> r.getId()).toList();
        if (roleIds.isEmpty()) return ResponseEntity.ok(ApiResponse.success(List.of()));
        List<Map<String, Object>> users = workflowEngineService.getUsersByRoles(roleIds, ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    @PutMapping("/engagements/{id}/sections/{sid}/assign")
    @Operation(summary = "Assign section to an auditor (cascades to children by default)")
    public ResponseEntity<ApiResponse<Void>> assignSection(
            @PathVariable Long id, @PathVariable Long sid,
            @RequestBody Map<String, Object> body) {
        var ctx      = utilityService.getLoggedInDataContext();
        Long auditorId = body.get("auditorId") != null
                ? Long.parseLong(body.get("auditorId").toString()) : null;
        boolean cascade = body.get("cascadeToChildren") == null
                || Boolean.parseBoolean(body.get("cascadeToChildren").toString());
        service.assignSection(id, sid, auditorId, cascade, ctx.getTenantId(), ctx.getId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PutMapping("/engagements/{id}/sections/{sid}/assign-auditee")
    @Operation(summary = "Assign a section (and its subtree) to an auditee user for evidence collection")
    public ResponseEntity<ApiResponse<Void>> assignSectionToAuditee(
            @PathVariable Long id, @PathVariable Long sid,
            @RequestBody Map<String, Object> body) {
        var ctx = utilityService.getLoggedInDataContext();
        Long auditeeUserId = body.get("auditeeUserId") != null
                ? Long.parseLong(body.get("auditeeUserId").toString()) : null;
        boolean cascade = body.get("cascadeToChildren") == null
                || Boolean.parseBoolean(body.get("cascadeToChildren").toString());
        service.assignAuditeeToSection(id, sid, auditeeUserId, cascade, ctx.getTenantId(), ctx.getId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/engagements/{id}/sections/{sid}/submit")
    @Operation(summary = "Auditor submits a section — locks test results")
    public ResponseEntity<ApiResponse<Void>> submitSection(
            @PathVariable Long id, @PathVariable Long sid,
            @RequestBody(required = false) Map<String, Object> body) {
        var ctx = utilityService.getLoggedInDataContext();
        boolean cascade = body != null && body.get("cascadeToChildren") != null
                && Boolean.parseBoolean(body.get("cascadeToChildren").toString());
        service.submitSection(id, sid, cascade, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/engagements/{id}/sections/{sid}/reopen")
    @Operation(summary = "Reopen a submitted section (CAE / lead auditor only)")
    public ResponseEntity<ApiResponse<Void>> reopenSection(
            @PathVariable Long id, @PathVariable Long sid) {
        var ctx = utilityService.getLoggedInDataContext();
        service.reopenSection(id, sid, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONTROLS (instance layer)
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/engagements/{id}/controls")
    @Operation(summary = "List control instances for an engagement")
    public ResponseEntity<ApiResponse<List<AuditControlInstance>>> listControls(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                controlInstanceRepository.findByEngagementId(id)));
    }

    @GetMapping("/engagements/{id}/controls/{cid}")
    @Operation(summary = "Get a single control instance")
    public ResponseEntity<ApiResponse<AuditControlInstance>> getControl(
            @PathVariable Long id, @PathVariable Long cid) {
        var control = controlInstanceRepository.findById(cid)
                .orElseThrow(() -> new ResourceNotFoundException("AuditControlInstance", cid));
        return ResponseEntity.ok(ApiResponse.success(control));
    }

    @PostMapping("/engagements/{id}/controls/{cid}/send-back-evidence")
    @Operation(summary = "Send control back to auditee for additional evidence (auditor action)")
    public ResponseEntity<ApiResponse<Void>> sendBackControlEvidence(
            @PathVariable Long id, @PathVariable Long cid,
            @RequestBody(required = false) Map<String, Object> body) {
        var ctx = utilityService.getLoggedInDataContext();
        String reason = body != null && body.get("reason") != null
                ? body.get("reason").toString() : null;
        service.sendBackControlEvidence(id, cid, reason, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/engagements/{id}/controls/{cid}/submit-evidence")
    @Operation(summary = "Auditee marks evidence as submitted for this control")
    public ResponseEntity<ApiResponse<Void>> submitControlEvidence(
            @PathVariable Long id, @PathVariable Long cid) {
        var ctx = utilityService.getLoggedInDataContext();
        service.submitControlEvidence(id, cid, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PutMapping("/engagements/{id}/controls/{cid}/test-result")
    @Operation(summary = "Record test result for a control")
    public ResponseEntity<ApiResponse<AuditControlInstance>> recordTestResult(
            @PathVariable Long id, @PathVariable Long cid,
            @RequestBody AuditControlTestRequest req) {
        var ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                service.recordTestResult(id, cid, req, ctx.getId(), ctx.getTenantId())));
    }

    @PutMapping("/engagements/{id}/controls/{cid}/assign-auditee")
    @Operation(summary = "Assign a control to an auditee user for evidence collection")
    public ResponseEntity<ApiResponse<Void>> assignControlToAuditee(
            @PathVariable Long id, @PathVariable Long cid,
            @RequestBody Map<String, Object> body) {
        var ctx = utilityService.getLoggedInDataContext();
        assertCanActOnControlSection(id, cid, ctx.getId(), "audit:control:assign-auditee");
        java.time.LocalDate dueDate = body.get("evidenceDueDate") != null
                ? java.time.LocalDate.parse(body.get("evidenceDueDate").toString())
                : null;
        service.assignAuditeeToControl(id, cid, body.get("auditeeUserId"), dueDate, ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PutMapping("/engagements/{id}/controls/{cid}/assign-auditor")
    @Operation(summary = "Assign a control to an auditor for review — explicit per-control assignment, not cascaded from section")
    public ResponseEntity<ApiResponse<Void>> assignControlToAuditor(
            @PathVariable Long id, @PathVariable Long cid,
            @RequestBody Map<String, Object> body) {
        var ctx = utilityService.getLoggedInDataContext();
        assertCanActOnControlSection(id, cid, ctx.getId(), "audit:control:assign-auditor");
        Object raw = body.get("auditorUserId");
        Long auditorId = raw != null ? Long.valueOf(raw.toString()) : null;
        service.assignAuditorToControl(id, cid, auditorId, ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/engagements/{id}/controls/bulk-assign")
    @Operation(summary = "Bulk-assign auditor and/or auditee to multiple controls in one call",
            description = "Provide either controlIds (explicit list) or sectionInstanceId " +
                    "(all controls under that section). Set auditorUserId and/or " +
                    "auditeeUserId. Eliminates N individual PUT calls for 50-100 control engagements.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkAssignControls(
            @PathVariable Long id,
            @RequestBody com.kashi.grc.audit.dto.request.BulkControlAssignRequest req) {
        var ctx = utilityService.getLoggedInDataContext();
        Long repControlId = null;
        if (req.getControlIds() != null && !req.getControlIds().isEmpty()) {
            repControlId = req.getControlIds().get(0);
        } else if (req.getSectionInstanceId() != null) {
            var repControls = controlInstanceRepository
                    .findBySectionInstanceIdOrderByOrderNoAsc(req.getSectionInstanceId());
            if (!repControls.isEmpty()) repControlId = repControls.get(0).getId();
        }
        if (repControlId != null) {
            String permission = req.getAuditorUserId() != null
                    ? "audit:control:assign-auditor" : "audit:control:assign-auditee";
            assertCanActOnControlSection(id, repControlId, ctx.getId(), permission);
        }
        int updated = service.bulkAssignControls(id, req, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("updated", updated)));
    }

    /**
     * Server-side mirror of the frontend's section-ownership gate.
     *
     * Reads the SAME assignment data the frontend already reads — the control's
     * parent section's assignedAuditorId / auditeeAssignedUserId — rather than
     * deriving any side/role logic. No hardcoded "AUDITOR side does X" rules:
     * the workflow's own assignment records (set via Steps 2/3, or by a lead
     * auditor/org-side user) are the single source of truth for who owns what.
     *
     * Pass rule:
     *   1. Caller holds the permission AND the resolved workflow permission set
     *      marks them as the engagement's lead/org-side actor for this step
     *      (i.e. they were ALSO allowed to assign sections in Steps 2/3 — proxy
     *      for "lead/org", reusing vc.permissions rather than hardcoding side) — OR
     *   2. Caller is recorded as the control's parent section's assigned auditor
     *      (for assign-auditee) — i.e. they own this section already.
     */
    private void assertCanActOnControlSection(Long engagementId, Long controlInstanceId,
                                              Long callerId, String requiredPermission) {
        var control = controlInstanceRepository.findById(controlInstanceId).orElse(null);
        if (control == null) return; // 404 will be raised downstream by the service

        var user = utilityService.getLoggedInUserWithRolesAndPermissions();
        var access = workflowAccessService.resolveForModule(user, "AUDIT_ENGAGEMENT", engagementId);
        List<String> perms = access != null ? access.getPermissions() : List.of();

        // Lead-auditor/org-side actors hold the SECTION-level assign permissions
        // (audit:section:assign-auditor / audit:section:assign-auditee) per the
        // workflow's own actor-role configuration (workflow_step_actor_roles).
        // Holding either is the workflow-driven signal for "this caller can assign
        // across the whole engagement" — not a hardcoded side/role check.
        boolean isSectionLevelAssigner = perms.contains("audit:section:assign-auditor")
                || perms.contains("audit:section:assign-auditee");
        if (isSectionLevelAssigner) return;

        // Walk up the section tree to find ownership — checks direct parent first,
        // then ancestors. Handles cases where cascade didn't reach intermediate nodes
        // (e.g. P1.1 assigned via P root section cascade).
        Long sectionId = control.getSectionInstanceId();
        boolean ownsSection = false;
        Long currentSectionId = sectionId;
        while (currentSectionId != null && !ownsSection) {
            var sec = sectionInstanceRepository.findById(currentSectionId).orElse(null);
            if (sec == null) break;
            ownsSection = requiredPermission.equals("audit:control:assign-auditor")
                    ? callerId.equals(sec.getAssignedAuditorId())
                    : callerId.equals(sec.getAuditeeAssignedUserId());
            currentSectionId = sec.getParentInstanceId(); // walk up to parent
        }

        if (!ownsSection) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "ACCESS_DENIED",
                    "You can only assign within sections you are the designated auditor for.");
        }
    }



    // ── ENGAGEMENT STATUS TRANSITIONS ─────────────────────────────────────────
    // These replace the generic PATCH /status for the specific lifecycle steps
    // so Screen Designer action buttons can target named endpoints with clear
    // intent and correct side-effect logic (timestamps, counter updates).

    @PostMapping("/engagements/{id}/activate")
    @Operation(summary = "Activate engagement — PLANNING → FIELDWORK, sets actualStart")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activate(@PathVariable Long id) {
        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        AuditEngagement e = engagementRepository.findById(id)
                .filter(eng -> eng.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", id));

        if (e.getStatus() != AuditEngagement.Status.PLANNING) {
            throw new BusinessException("INVALID_TRANSITION",
                    "Engagement must be in PLANNING status to activate. Current: " + e.getStatus(),
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }

        e.setStatus(AuditEngagement.Status.FIELDWORK);
        e.setActualStart(LocalDateTime.now());
        engagementRepository.save(e);

        // Advance the workflow: approve ONLY the current user's own PENDING ACTOR task.
        // "Engagement Setup" is a FILL step — clicking Activate IS the GRC Manager's
        // completion action for their own task. Other actors (e.g. co-auditors) must
        // approve their own tasks from their own inbox.
        // For ANY_ONE steps, this one approval satisfies the step and expires other tasks.
        // For ALL steps, the step waits until every actor has approved from their own inbox.
        if (e.getWorkflowInstanceId() != null) {
            stepInstanceRepository.findByWorkflowInstanceIdAndStatus(
                    e.getWorkflowInstanceId(), com.kashi.grc.workflow.enums.StepStatus.IN_PROGRESS
            ).forEach(stepInstance -> {
                taskInstanceRepository
                        .findByStepInstanceIdAndStatus(stepInstance.getId(), TaskStatus.PENDING)
                        .stream()
                        .filter(t -> t.getTaskRole() == TaskRole.ACTOR)
                        .filter(t -> ctx.getId().equals(t.getAssignedUserId())) // ← ONLY current user's task
                        .forEach(t -> {
                            try {
                                TaskActionRequest req = new TaskActionRequest();
                                req.taskInstanceId = t.getId();
                                req.actionType     = ActionType.APPROVE;
                                workflowEngineService.performAction(req, ctx.getId());
                                log.info("[AUDIT-ENG] Activate → approved task #{} (step '{}')",
                                        t.getId(), stepInstance.getSnapName());
                            } catch (Exception ex) {
                                log.warn("[AUDIT-ENG] Could not approve task #{} on activate: {}",
                                        t.getId(), ex.getMessage());
                            }
                        });
            });
        }

        log.info("[AUDIT-ENG] Activated | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("id", id, "status", e.getStatus(), "actualStart", e.getActualStart())));
    }

    @PostMapping("/engagements/{id}/start-evidence-review")
    @Operation(summary = "Move to evidence review — FIELDWORK → EVIDENCE_REVIEW")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startEvidenceReview(@PathVariable Long id) {
        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        AuditEngagement e = engagementRepository.findById(id)
                .filter(eng -> eng.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", id));

        if (e.getStatus() != AuditEngagement.Status.FIELDWORK) {
            throw new BusinessException("INVALID_TRANSITION",
                    "Engagement must be in FIELDWORK to start evidence review. Current: " + e.getStatus(),
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }

        e.setStatus(AuditEngagement.Status.EVIDENCE_REVIEW);
        engagementRepository.save(e);

        log.info("[AUDIT-ENG] Evidence review started | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "status", e.getStatus())));
    }

    @PostMapping("/engagements/{id}/start-draft-report")
    @Operation(summary = "Move to draft report — EVIDENCE_REVIEW → DRAFT_REPORT")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startDraftReport(@PathVariable Long id) {
        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        AuditEngagement e = engagementRepository.findById(id)
                .filter(eng -> eng.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", id));

        if (e.getStatus() != AuditEngagement.Status.EVIDENCE_REVIEW) {
            throw new BusinessException("INVALID_TRANSITION",
                    "Engagement must be in EVIDENCE_REVIEW to draft report. Current: " + e.getStatus(),
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }

        e.setStatus(AuditEngagement.Status.DRAFT_REPORT);
        e.setSubmittedAt(LocalDateTime.now());
        e.setSubmittedBy(ctx.getId());
        engagementRepository.save(e);

        log.info("[AUDIT-ENG] Draft report started | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "status", e.getStatus())));
    }

    @PostMapping("/engagements/{id}/complete")
    @Operation(summary = "Complete engagement — DRAFT_REPORT/FINAL_REPORT → CLOSED, sets completedAt")
    public ResponseEntity<ApiResponse<Map<String, Object>>> complete(@PathVariable Long id) {
        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        AuditEngagement e = engagementRepository.findById(id)
                .filter(eng -> eng.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", id));

        if (e.getStatus() == AuditEngagement.Status.CLOSED
                || e.getStatus() == AuditEngagement.Status.CANCELLED) {
            throw new BusinessException("INVALID_TRANSITION",
                    "Engagement is already " + e.getStatus(),
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }

        e.setStatus(AuditEngagement.Status.CLOSED);
        e.setCompletedAt(LocalDateTime.now());
        engagementRepository.save(e);

        log.info("[AUDIT-ENG] Completed | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("id", id, "status", e.getStatus(), "completedAt", e.getCompletedAt())));
    }

    @PostMapping("/engagements/{id}/cancel")
    @Operation(summary = "Cancel engagement — any status → CANCELLED")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(@PathVariable Long id) {
        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        AuditEngagement e = engagementRepository.findById(id)
                .filter(eng -> eng.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", id));

        e.setStatus(AuditEngagement.Status.CANCELLED);
        engagementRepository.save(e);

        log.info("[AUDIT-ENG] Cancelled | id={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("id", id, "status", e.getStatus())));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STEP 9 — Draft Report Review
    // PUT /v1/audit/engagements/{id}/report-review
    // Side: AUDITOR (section auditors Rohit/Kavya), approval_type=ALL
    // Saves audit opinion + narrative. Completing the task advances to Step 10.
    // ══════════════════════════════════════════════════════════════════════════

    @PutMapping("/engagements/{id}/report-review")
    @Operation(summary = "Step 9 — Submit draft report review (audit opinion + narrative)")
    public ResponseEntity<ApiResponse<Void>> submitReportReview(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        AuditEngagement e = engagementRepository.findById(id)
                .filter(eng -> eng.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("AuditEngagement", id));

        if (body.containsKey("auditOpinion"))    e.setAuditOpinion((String) body.get("auditOpinion"));
        if (body.containsKey("overallRating"))   e.setOverallRating((String) body.get("overallRating"));
        if (body.containsKey("executiveSummary"))e.setExecutiveSummary((String) body.get("executiveSummary"));
        if (body.containsKey("reviewComments"))  e.setReviewComments((String) body.get("reviewComments"));
        if (body.containsKey("scopeLimitations"))e.setScopeLimitations((String) body.get("scopeLimitations"));
        if (body.containsKey("reportVersion") && body.get("reportVersion") != null) {
            String rv = body.get("reportVersion").toString().trim();
            if (!rv.isEmpty()) e.setReportVersion(Integer.parseInt(rv));
        }
        e.setReviewedBy(ctx.getId());
        e.setReviewedAt(LocalDateTime.now());
        engagementRepository.save(e);

        log.info("[AUDIT-ENG] Report review submitted | engagementId={} | opinion={} | by={}",
                id, e.getAuditOpinion(), ctx.getId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STEP 11 — Cross-Framework Consolidation
    // PUT /v1/audit/project-instances/{id}/consolidation
    // Side: ORGANIZATION (project owner), ENTITY_OWNER, REVIEW
    // ══════════════════════════════════════════════════════════════════════════

    @PutMapping("/project-instances/{id}/consolidation")
    @Operation(summary = "Step 11 — Submit cross-framework consolidation notes")
    public ResponseEntity<ApiResponse<Void>> submitConsolidation(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        AuditProjectInstance p = projectInstanceRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProjectInstance", id));

        if (body.containsKey("crossFrameworkNotes")) p.setCrossFrameworkNotes((String) body.get("crossFrameworkNotes"));
        if (body.containsKey("programmeRisk"))       p.setProgrammeRisk((String) body.get("programmeRisk"));
        projectInstanceRepository.save(p);

        log.info("[AUDIT-PROJ-INST] Consolidation submitted | instanceId={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STEP 12 — Management Response
    // PUT /v1/audit/project-instances/{id}/management-response
    // Side: ORGANIZATION, ENTITY_OWNER, FILL
    // ══════════════════════════════════════════════════════════════════════════

    @PutMapping("/project-instances/{id}/management-response")
    @Operation(summary = "Step 12 — Submit formal management response to the audit report")
    public ResponseEntity<ApiResponse<Void>> submitManagementResponse(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        AuditProjectInstance p = projectInstanceRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProjectInstance", id));

        if (body.containsKey("managementResponse"))   p.setManagementResponse((String) body.get("managementResponse"));
        if (body.containsKey("acceptanceOfFindings")) p.setAcceptanceOfFindings((String) body.get("acceptanceOfFindings"));
        if (body.containsKey("correctiveActions"))    p.setCorrectiveActions((String) body.get("correctiveActions"));
        if (body.containsKey("committedClosureDate") && body.get("committedClosureDate") != null) {
            String d = body.get("committedClosureDate").toString().trim();
            if (!d.isEmpty()) p.setCommittedClosureDate(java.time.LocalDate.parse(d));
        }
        projectInstanceRepository.save(p);

        log.info("[AUDIT-PROJ-INST] Management response submitted | instanceId={} | by={}", id, ctx.getId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STEP 13 — Executive Sign-off
    // PUT /v1/audit/project-instances/{id}/sign-off
    // Side: ORGANIZATION (CISO), ROLE_BASED, APPROVE
    // ══════════════════════════════════════════════════════════════════════════

    @PutMapping("/project-instances/{id}/sign-off")
    @Operation(summary = "Step 13 — CISO executive sign-off and programme closure")
    public ResponseEntity<ApiResponse<Void>> submitExecutiveSignOff(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        var ctx       = utilityService.getLoggedInDataContext();
        Long tenantId = ctx.getTenantId();

        AuditProjectInstance p = projectInstanceRepository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditProjectInstance", id));

        if (body.containsKey("executiveSignOff"))  p.setExecutiveSignOff((String) body.get("executiveSignOff"));
        if (body.containsKey("programmeOutcome"))  p.setProgrammeOutcome((String) body.get("programmeOutcome"));
        if (body.containsKey("closureStatement"))  p.setClosureStatement((String) body.get("closureStatement"));
        if (body.containsKey("nextAuditDue") && body.get("nextAuditDue") != null) {
            String d = body.get("nextAuditDue").toString().trim();
            if (!d.isEmpty()) p.setNextAuditDue(java.time.LocalDate.parse(d));
        }
        p.setSignedOffBy(ctx.getId());
        p.setSignedOffAt(LocalDateTime.now());
        projectInstanceRepository.save(p);

        log.info("[AUDIT-PROJ-INST] Executive sign-off | instanceId={} | outcome={} | by={}",
                id, p.getProgrammeOutcome(), ctx.getId());
        return ResponseEntity.ok(ApiResponse.success());
    }
}