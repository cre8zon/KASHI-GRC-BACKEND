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
import com.kashi.grc.workflow.dto.request.TaskActionRequest;
import com.kashi.grc.workflow.enums.ActionType;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import com.kashi.grc.workflow.repository.StepInstanceRepository;
import com.kashi.grc.workflow.repository.TaskInstanceRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
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
    private final AuditTemplateRepository               templateRepository;
    private final DbRepository                          dbRepository;
    private final UtilityService                        utilityService;
    private final WorkflowEngineService                 workflowEngineService;
    private final TaskInstanceRepository                taskInstanceRepository;
    private final StepInstanceRepository                stepInstanceRepository;

    // ══════════════════════════════════════════════════════════════════════════
    // PROJECTS — org-scoped creation, global + own visibility
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/projects")
    @Operation(summary = "Create an org-scoped audit project — tenantId = caller's tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createProject(
            @Valid @RequestBody AuditProjectRequest req) {

        var ctx = utilityService.getLoggedInDataContext();
        // Org projects are always tenant-scoped — use ctx.getTenantId(), never null here
        Long tenantId = ctx.getTenantId();

        // Use MAX on ref to get a globally unique sequence — avoids collisions from count-based approach
        int year = LocalDateTime.now().getYear();
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
    @Operation(summary = "List projects — global (tenantId=null) + caller's own, with filters")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listProjects(
            @RequestParam Map<String, String> allParams) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();
        Long tenantId    = ctx.getTenantId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AuditProject.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    var preds = new ArrayList<jakarta.persistence.criteria.Predicate>();
                    if (!isSystem) {
                        // Org users: global OR own tenant
                        preds.add(cb.or(
                                cb.isNull(root.get("tenantId")),
                                cb.equal(root.get("tenantId"), tenantId)
                        ));
                    }
                    // Platform Admin: no filter — sees all
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
                p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          p.getId());
                    m.put("projectRef",  p.getProjectRef());
                    m.put("name",        p.getName());
                    m.put("description", p.getDescription());
                    m.put("status",      p.getStatus());
                    m.put("ownerId",     p.getOwnerId());
                    m.put("tenantId",    p.getTenantId());
                    m.put("global",      p.getTenantId() == null);
                    m.put("plannedStart",p.getPlannedStart());
                    m.put("plannedEnd",  p.getPlannedEnd());
                    m.put("createdAt",   p.getCreatedAt());
                    return m;
                }
        )));
    }

    @GetMapping("/projects/{projectId}")
    @Operation(summary = "Get project detail — includes frozen snapshot if engagements have started")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProject(
            @PathVariable Long projectId) {

        boolean isSystem = utilityService.isSystemUser();
        var ctx          = utilityService.getLoggedInDataContext();

        AuditProject project = projectRepository.findById(projectId)
                .filter(p -> isSystem || p.getTenantId() == null
                        || p.getTenantId().equals(ctx.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditProject", projectId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id",          project.getId());
        result.put("projectRef",  project.getProjectRef());
        result.put("name",        project.getName());
        result.put("description", project.getDescription());
        result.put("status",      project.getStatus());
        result.put("ownerId",     project.getOwnerId());
        result.put("tenantId",    project.getTenantId());
        result.put("global",      project.getTenantId() == null);
        result.put("plannedStart",project.getPlannedStart());
        result.put("plannedEnd",  project.getPlannedEnd());
        result.put("createdAt",   project.getCreatedAt());

        // Include frozen snapshot if engagements have started
        projectInstanceRepository.findByOriginalProjectId(projectId).ifPresent(inst -> {
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

    // ══════════════════════════════════════════════════════════════════════════
    // PROJECT-TEMPLATE PLANNING (org adopts templates into their project)
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
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listEngagements(
            @RequestParam Map<String, String> allParams) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                AuditEngagement.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    var preds = new ArrayList<jakarta.persistence.criteria.Predicate>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    if (allParams.containsKey("projectId"))
                        preds.add(cb.equal(root.get("projectId"),
                                Long.parseLong(allParams.get("projectId"))));
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
        result.put("tenantId",           e.getTenantId());
        result.put("createdAt",          e.getCreatedAt());

        // Attach frozen project snapshot for context — same as before
        if (e.getProjectInstanceId() != null) {
            projectInstanceRepository.findById(e.getProjectInstanceId()).ifPresent(inst -> {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("id",                  inst.getId());
                snapshot.put("projectNameSnapshot",  inst.getProjectNameSnapshot());
                snapshot.put("projectRefSnapshot",   inst.getProjectRefSnapshot());
                snapshot.put("snapshottedAt",        inst.getSnapshottedAt());
                result.put("projectSnapshot", snapshot);
            });
        }

        return ResponseEntity.ok(ApiResponse.success(result));
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
        service.assignSection(id, sid, auditorId, cascade, ctx.getTenantId());
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
        service.assignAuditeeToSection(id, sid, auditeeUserId, cascade, ctx.getTenantId());
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
        java.time.LocalDate dueDate = body.get("evidenceDueDate") != null
                ? java.time.LocalDate.parse(body.get("evidenceDueDate").toString())
                : null;
        service.assignAuditeeToControl(id, cid, body.get("auditeeUserId"), dueDate, ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
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
                    HttpStatus.UNPROCESSABLE_ENTITY);
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
                    HttpStatus.UNPROCESSABLE_ENTITY);
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
                    HttpStatus.UNPROCESSABLE_ENTITY);
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
                    HttpStatus.UNPROCESSABLE_ENTITY);
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
}