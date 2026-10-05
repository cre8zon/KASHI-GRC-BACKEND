package com.kashi.grc.risk.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.risk.domain.Risk;
import com.kashi.grc.risk.dto.*;
import com.kashi.grc.risk.service.RiskAdoptService;
import com.kashi.grc.risk.service.RiskService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.workflow.dto.response.WorkflowHistoryResponse;
import com.kashi.grc.workflow.repository.WorkflowInstanceRepository;
import com.kashi.grc.workflow.service.WorkflowEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Risk register API.
 *
 * ── THE ENDPOINT LIST IS NOT FREE-FORM ────────────────────────────────────
 * Every path below is named by a row already seeded in risk_register_seed.sql.
 * Renaming one here silently breaks a button with no error on either side:
 *
 *   ui_forms.submit_url
 *     POST /v1/risks                        risk_create_form
 *     PUT  /v1/risks/{id}                   risk_detail_header, _tab_overview
 *     PUT  /v1/risks/{id}/assessment        risk_detail_tab_assessment
 *     PUT  /v1/risks/{id}/treatment         risk_detail_tab_treatment
 *     POST /v1/risks/library/adopt-all      risk_adopt_all_form
 *
 *   ui_actions.api_endpoint
 *     POST /v1/risks/{id}/assess            RISK_ASSESS
 *     POST /v1/risks/{id}/plan-treatment    RISK_PLAN_TREATMENT
 *     POST /v1/risks/{id}/mark-treated      RISK_MARK_TREATED
 *     POST /v1/risks/{id}/accept            RISK_ACCEPT
 *     POST /v1/risks/{id}/close             RISK_CLOSE
 *     POST /v1/risks/{id}/reopen            RISK_REOPEN
 *     POST /v1/risks/{id}/controls          RISK_LINK_CONTROL
 *
 *   module_blueprints.api_base_path = /v1/risks
 *     drives GET (list), GET /{id}, and GET /{id}/history via the generic
 *     UniversalModulePage plumbing.
 *
 * No @PreAuthorize, matching every other module controller here. The risk:*
 * permissions gate the buttons through ui_actions.required_permission.
 * Tenancy, row ownership and transition legality are enforced in RiskService.
 */
@Slf4j
@RestController
@RequestMapping("/v1/risks")
@Tag(name = "Risk Register", description = "ISO 27005 / Clause 6.1 risk register — identify, assess, treat, accept, close")
@RequiredArgsConstructor
public class RiskController {

    private final RiskService                riskService;
    private final RiskAdoptService           riskAdoptService;
    private final UtilityService             utilityService;
    private final UserRepository             userRepository;
    private final DbRepository               dbRepository;
    private final WorkflowEngineService      workflowEngineService;
    private final WorkflowInstanceRepository instanceRepository;

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Raise a risk into the register")
    public ResponseEntity<ApiResponse<RiskResponse>> create(@Valid @RequestBody RiskRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        RiskResponse response = riskService.create(req, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    /**
     * Filterable list. Mapped inline rather than through RiskService.toResponse
     * so DbRepository's pagination, search and the guest/vendor row scope it
     * applies in buildPredicates all stay on the query — building full detail
     * payloads per row would also fire a control lookup per risk.
     *
     * origin selects which side of the library split to return:
     *   GLOBAL -> platform scenarios (tenant_id IS NULL)
     *   ORG    -> the tenant's own register
     *   absent -> the tenant's own register
     *
     * origin, not a bespoke param. ModuleListView forwards exactly seven keys
     * (search, skip, take, frameworkRef, origin, sortBy, sortDirection) and
     * drops everything else, so the `scope` param this once used never left the
     * browser -- the library screen fetched the tenant register and rendered
     * "0 records". origin is also what the All / Platform / Custom toggle
     * writes, so one param drives both.
     */
    @GetMapping
    @Operation(summary = "List risks — filter by status, category, treatmentOption, ownerId; origin=GLOBAL for the platform library")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> list(
            @RequestParam Map<String, String> allParams) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        boolean libraryScope = "GLOBAL".equalsIgnoreCase(allParams.get("origin"));

        // Owner names for the list column. One query for the page rather than
        // one per row — the list layout's ownerName column would otherwise cost
        // a lookup per risk on every page load.
        Map<Long, String> nameCache = new HashMap<>();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                Risk.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
                    if (libraryScope) {
                        preds.add(cb.isNull(root.get("tenantId")));
                    } else {
                        preds.add(cb.equal(root.get("tenantId"), tenantId));
                    }
                    preds.add(cb.isFalse(root.get("isDeleted")));

                    if (allParams.containsKey("status")) {
                        preds.add(cb.equal(root.get("status"),
                                Risk.Status.valueOf(allParams.get("status").toUpperCase())));
                    }
                    if (allParams.containsKey("category")) {
                        preds.add(cb.equal(root.get("category"),
                                allParams.get("category").toUpperCase()));
                    }
                    if (allParams.containsKey("riskSource")) {
                        preds.add(cb.equal(root.get("riskSource"),
                                allParams.get("riskSource").toUpperCase()));
                    }
                    if (allParams.containsKey("treatmentOption")) {
                        preds.add(cb.equal(root.get("treatmentOption"),
                                allParams.get("treatmentOption").toUpperCase()));
                    }
                    if (allParams.containsKey("ownerId")) {
                        preds.add(cb.equal(root.get("ownerId"),
                                Long.parseLong(allParams.get("ownerId"))));
                    }
                    return preds;
                },
                // Searchable/sortable fields. filterBy and sortBy from the query
                // string resolve against these keys only — a key absent here is
                // silently ignored by DbRepository, so the list layout's filter
                // keys must all appear.
                (cb, root) -> {
                    Map<String, jakarta.persistence.criteria.Path<?>> fields = new HashMap<>();
                    fields.put("title",           root.get("title"));
                    fields.put("risk_ref",        root.get("riskRef"));
                    fields.put("riskref",         root.get("riskRef"));
                    fields.put("category",        root.get("category"));
                    fields.put("status",          root.get("status"));
                    fields.put("treatmentoption", root.get("treatmentOption"));
                    fields.put("inherentscore",   root.get("inherentScore"));
                    fields.put("residualscore",   root.get("residualScore"));
                    fields.put("nextreviewdate",  root.get("nextReviewDate"));
                    fields.put("created_at",      root.get("createdAt"));
                    return fields;
                },
                r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",                 r.getId());
                    m.put("riskRef",            r.getRiskRef());
                    m.put("title",              r.getTitle());
                    m.put("category",           r.getCategory()   != null ? r.getCategory()   : "");
                    m.put("riskSource",         r.getRiskSource() != null ? r.getRiskSource() : "");
                    m.put("status",             r.getStatus());
                    m.put("inherentLikelihood", r.getInherentLikelihood());
                    m.put("inherentImpact",     r.getInherentImpact());
                    m.put("inherentScore",      r.getInherentScore());
                    m.put("residualScore",      r.getResidualScore());
                    m.put("treatmentOption",    r.getTreatmentOption() != null ? r.getTreatmentOption() : "");
                    m.put("ownerId",            r.getOwnerId());
                    m.put("ownerName",          resolveName(r.getOwnerId(), nameCache));
                    m.put("ownerTeam",          r.getOwnerTeam() != null ? r.getOwnerTeam() : "");
                    // The UCF anchor. On a library row this is the only control
                    // linkage there is -- risk_control_links rows are created at
                    // adoption, not on the platform row.
                    m.put("controlTags",        r.getControlTags() != null ? r.getControlTags() : "");
                    m.put("nextReviewDate",     r.getNextReviewDate());
                    m.put("sourceRiskId",       r.getSourceRiskId());
                    m.put("origin",             r.getTenantId() == null ? "GLOBAL" : "ORG");
                    // editable = false makes UniversalModulePage drop the
                    // collaboration tabs on a platform row, the same way it does
                    // for a platform policy.
                    m.put("editable",           r.getTenantId() != null);
                    m.put("createdAt",          r.getCreatedAt());
                    m.put("updatedAt",          r.getUpdatedAt());
                    return m;
                }
        )));
    }

    private String resolveName(Long userId, Map<Long, String> cache) {
        if (userId == null) return "";
        return cache.computeIfAbsent(userId, id -> userRepository.findById(id)
                .map(u -> {
                    String full = u.getFullName();
                    return (full != null && !full.isBlank()) ? full : u.getEmail();
                })
                .orElse(""));
    }

    // ── Get single ────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(summary = "Get one risk with linked controls and assignment state")
    public ResponseEntity<ApiResponse<RiskResponse>> getById(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.getById(id, ctx.getTenantId(), ctx.getId())));
    }

    // ── Update (header + overview tabs) ───────────────────────────────────────

    @PutMapping("/{id}")
    @Operation(summary = "Update a risk — title, category, source, owner, tags, review cycle")
    public ResponseEntity<ApiResponse<RiskResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody RiskRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.update(id, req, ctx.getId(), ctx.getTenantId())));
    }

    // ── Assessment tab + action ───────────────────────────────────────────────

    @PutMapping("/{id}/assessment")
    @Operation(summary = "Save the assessment tab — does not change status")
    public ResponseEntity<ApiResponse<RiskResponse>> saveAssessment(
            @PathVariable Long id,
            @Valid @RequestBody RiskAssessmentRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.saveAssessment(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/assess")
    @Operation(summary = "Record assessment — IDENTIFIED to ASSESSED")
    public ResponseEntity<ApiResponse<RiskResponse>> assess(
            @PathVariable Long id,
            @RequestBody(required = false) RiskAssessmentRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.assess(id, req, ctx.getId(), ctx.getTenantId())));
    }

    // ── Treatment tab + actions ───────────────────────────────────────────────

    @PutMapping("/{id}/treatment")
    @Operation(summary = "Save the treatment tab — does not change status")
    public ResponseEntity<ApiResponse<RiskResponse>> saveTreatment(
            @PathVariable Long id,
            @Valid @RequestBody RiskTreatmentRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.saveTreatment(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/plan-treatment")
    @Operation(summary = "Plan treatment — ASSESSED to TREATMENT_PLANNED")
    public ResponseEntity<ApiResponse<RiskResponse>> planTreatment(
            @PathVariable Long id,
            @RequestBody(required = false) RiskTreatmentRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.planTreatment(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/mark-treated")
    @Operation(summary = "Mark treated — TREATMENT_PLANNED to TREATED")
    public ResponseEntity<ApiResponse<RiskResponse>> markTreated(
            @PathVariable Long id,
            @RequestBody(required = false) RiskTreatmentRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.markTreated(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/accept")
    @Operation(summary = "Accept the risk — ASSESSED or TREATMENT_PLANNED to ACCEPTED")
    public ResponseEntity<ApiResponse<RiskResponse>> accept(
            @PathVariable Long id,
            @RequestBody(required = false) RiskTreatmentRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.accept(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close the risk — TREATED or ACCEPTED to CLOSED")
    public ResponseEntity<ApiResponse<RiskResponse>> close(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.close(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/reopen")
    @Operation(summary = "Reopen the risk — CLOSED or ACCEPTED to IDENTIFIED")
    public ResponseEntity<ApiResponse<RiskResponse>> reopen(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.reopen(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    /** UniversalModulePage posts requires_remarks text as "remarks". */
    private String remarksOf(Map<String, Object> body) {
        if (body == null) return null;
        Object v = body.get("remarks");
        return v != null ? String.valueOf(v) : null;
    }

    // ── Linked controls ───────────────────────────────────────────────────────

    @GetMapping("/{id}/controls")
    @Operation(summary = "Controls linked to this risk, with latest observed effectiveness")
    public ResponseEntity<ApiResponse<List<RiskResponse.LinkedControl>>> listControls(
            @PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.listLinkedControls(id, tenantId)));
    }

    @PostMapping("/{id}/controls")
    @Operation(summary = "Link a library control that treats this risk")
    public ResponseEntity<ApiResponse<RiskResponse.LinkedControl>> linkControl(
            @PathVariable Long id,
            @Valid @RequestBody RiskControlLinkRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                riskService.linkControl(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @DeleteMapping("/{id}/controls/{controlId}")
    @Operation(summary = "Unlink a control from this risk")
    public ResponseEntity<ApiResponse<Void>> unlinkControl(
            @PathVariable Long id,
            @PathVariable Long controlId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        riskService.unlinkControl(id, controlId, tenantId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Linked issues ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/issues")
    @Operation(summary = "Issues raised against this risk (issues.source_entity_type = RISK)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listIssues(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.listLinkedIssues(id, tenantId)));
    }

    // ── Linked assets ─────────────────────────────────────────────────────────

    /**
     * Assets this risk is exposed to. Served to the generic LinkedEntitiesTab,
     * which derives the path from the tab key: a tabs_json key of
     * "linked-assets" calls {apiBasePath}/{id}/linked-assets.
     *
     * Read-only. Linking lives on POST /v1/assets/{id}/risks, because the Asset
     * module owns risk_asset_links.
     */
    @GetMapping("/{id}/linked-assets")
    @Operation(summary = "Assets this risk is exposed to (ISO 27005 asset-based identification)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listLinkedAssets(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                riskService.listLinkedAssets(id, tenantId)));
    }

    // ── Library adoption ──────────────────────────────────────────────────────

    @PostMapping("/library/adopt-all")
    @Operation(summary = "Adopt every platform risk scenario not already adopted by this tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adoptAll(
            @RequestBody(required = false) RiskAdoptRequest req) {

        User ctx = utilityService.getLoggedInDataContext();
        RiskAdoptService.AdoptResult result = riskAdoptService.adoptAll(
                ctx.getTenantId(), ctx.getId(),
                req != null ? req.getOwnerId()   : null,
                req != null ? req.getOwnerTeam() : null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("created",  result.created());
        payload.put("skipped",  result.skipped());
        payload.put("failed",   result.failed());
        payload.put("problems", result.problems());
        payload.put("message",  result.created() == 0 && result.skipped() > 0
                ? "Every platform risk is already in your register."
                : result.created() + " risk(s) adopted, " + result.skipped() + " already present.");

        // WARNING, not SUCCESS, when some rows failed — the caller gets the
        // counts either way, but a partial run must not render as a clean tick.
        return ResponseEntity.ok(result.failed() > 0
                ? ApiResponse.warning(payload)
                : ApiResponse.success(payload));
    }

    /**
     * Adopt ONE library scenario. Backs the per-row Adopt button.
     *
     * The response carries the new risk, so ModuleListView's runRowAction can
     * read `id` off it and land the user on their copy rather than leaving them
     * on the library list wondering where it went.
     *
     * This is the selective counterpart to /library/adopt-all. With 130
     * scenarios in the library, adopt-all is the wrong default for most
     * tenants — a register of 130 unassessed risks gets abandoned, not worked.
     */
    @PostMapping("/library/{id}/adopt")
    @Operation(summary = "Adopt one platform risk scenario into this tenant's register")
    public ResponseEntity<ApiResponse<RiskResponse>> adoptOne(
            @PathVariable Long id,
            @RequestBody(required = false) RiskAdoptRequest req) {

        User ctx = utilityService.getLoggedInDataContext();
        Risk copy = riskAdoptService.adoptOneById(
                id, ctx.getTenantId(), ctx.getId(),
                req != null ? req.getOwnerId()   : null,
                req != null ? req.getOwnerTeam() : null);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                riskService.toResponse(copy, ctx.getTenantId(), ctx.getId())));
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    @Operation(summary = "Register stats — counts by status and category, review-overdue count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(riskService.getStats(tenantId)));
    }

    // ── History ───────────────────────────────────────────────────────────────

    /**
     * The generic HistoryTab calls GET {apiBasePath}/{id}/history. Returns an
     * empty list rather than a 404 when no workflow has ever run on the risk —
     * which is the normal case until a RISK workflow is configured.
     */
    @GetMapping("/{id}/history")
    @Operation(summary = "Workflow history for a risk across all cycles")
    public ResponseEntity<ApiResponse<List<WorkflowHistoryResponse>>> history(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        var instances = instanceRepository
                .findByTenantIdAndEntityTypeAndEntityId(tenantId, "RISK", id);
        if (instances.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<Long> instanceIds = instances.stream()
                .map(com.kashi.grc.workflow.domain.WorkflowInstance::getId)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(
                workflowEngineService.getFullHistoryForInstances(instanceIds)));
    }
}