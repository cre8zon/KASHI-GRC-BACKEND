package com.kashi.grc.personnel.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.personnel.domain.Personnel;
import com.kashi.grc.personnel.dto.*;
import com.kashi.grc.personnel.repository.PersonnelRepository;
import com.kashi.grc.personnel.service.PersonnelService;
import com.kashi.grc.usermanagement.domain.User;
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
 * Personnel roster API — ISO 27001 A.6.1, A.6.2, A.6.5, A.5.11.
 *
 * ── THE ENDPOINT LIST IS NOT FREE-FORM ────────────────────────────────────
 * Every path is named by a row in 14_personnel_seed.sql:
 *
 *   ui_forms.submit_url
 *     POST   /v1/personnel                    personnel_create_form
 *     PUT    /v1/personnel/{id}               personnel_detail_header, _tab_overview
 *     PUT    /v1/personnel/{id}/employment    personnel_detail_tab_employment
 *     PUT    /v1/personnel/{id}/screening     personnel_detail_tab_screening
 *     POST   /v1/personnel/{id}/exclusions    personnel_exclusion_form
 *     POST   /v1/personnel/{id}/assets        personnel_asset_form
 *
 *   ui_actions.api_endpoint
 *     POST /v1/personnel/{id}/activate               PERSON_ACTIVATE
 *     POST /v1/personnel/{id}/never-started          PERSON_NEVER_STARTED
 *     POST /v1/personnel/{id}/start-leave            PERSON_START_LEAVE
 *     POST /v1/personnel/{id}/return-leave           PERSON_RETURN_LEAVE
 *     POST /v1/personnel/{id}/give-notice            PERSON_GIVE_NOTICE
 *     POST /v1/personnel/{id}/start-offboarding      PERSON_START_OFFBOARDING
 *     POST /v1/personnel/{id}/complete-offboarding   PERSON_COMPLETE_OFFBOARDING
 *     POST /v1/personnel/{id}/exclusions             PERSON_ADD_EXCLUSION (__formKey)
 *     POST /v1/personnel/{id}/assets                 PERSON_ASSIGN_ASSET  (__formKey)
 *
 *   ui_layouts.tabs_json — served by the generic LinkedEntitiesTab, which
 *   derives {apiBasePath}/{id}/<tabKey> from the key:
 *     GET /v1/personnel/{id}/linked-exclusions
 *     GET /v1/personnel/{id}/linked-assets
 *
 * /v1/personnel is also the lookup endpoint for the managerPersonnelId field on
 * its own forms, which is why the list has to work with a bare search param.
 */
@Slf4j
@RestController
@RequestMapping("/v1/personnel")
@Tag(name = "Personnel", description = "The roster — screening, agreements, asset custody and offboarding evidence")
@RequiredArgsConstructor
public class PersonnelController {

    private final PersonnelService    personnelService;
    private final PersonnelRepository personnelRepository;
    private final UtilityService      utilityService;
    private final DbRepository        dbRepository;

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Add a person to the roster")
    public ResponseEntity<ApiResponse<PersonnelResponse>> create(
            @Valid @RequestBody PersonnelRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                personnelService.create(req, ctx.getId(), ctx.getTenantId())));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    /**
     * Mapped inline so DbRepository's pagination stays on the query.
     *
     * complianceStatus is computed for the whole page in one batch after the
     * fact. Doing it inside the row mapper would fire an exclusion query per
     * person — the N+1 the task inbox had to be rescued from.
     */
    @GetMapping
    @Operation(summary = "List personnel — filter by status, employmentType, screening, compliance, source")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> list(
            @RequestParam Map<String, String> allParams) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Map<Long, String> nameCache = new HashMap<>();

        PaginatedResponse<Map<String, Object>> page = dbRepository.findAll(
                Personnel.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    preds.add(cb.isFalse(root.get("isDeleted")));

                    if (allParams.containsKey("status")) {
                        preds.add(cb.equal(root.get("status"),
                                Personnel.Status.valueOf(allParams.get("status").toUpperCase())));
                    }
                    if (allParams.containsKey("employmentType")) {
                        preds.add(cb.equal(root.get("employmentType"),
                                allParams.get("employmentType").toUpperCase()));
                    }
                    if (allParams.containsKey("backgroundCheckStatus")) {
                        preds.add(cb.equal(root.get("backgroundCheckStatus"),
                                allParams.get("backgroundCheckStatus").toUpperCase()));
                    }
                    if (allParams.containsKey("sourceSystem")) {
                        preds.add(cb.equal(root.get("sourceSystem"),
                                allParams.get("sourceSystem").toUpperCase()));
                    }
                    if (allParams.containsKey("department")) {
                        preds.add(cb.equal(root.get("department"), allParams.get("department")));
                    }
                    if (allParams.containsKey("managerPersonnelId")) {
                        preds.add(cb.equal(root.get("managerPersonnelId"),
                                Long.parseLong(allParams.get("managerPersonnelId"))));
                    }
                    if ("true".equalsIgnoreCase(allParams.get("hasPrivilegedAccess"))) {
                        preds.add(cb.isTrue(root.get("hasPrivilegedAccess")));
                    }
                    if ("true".equalsIgnoreCase(allParams.get("inScopeOnly"))) {
                        preds.add(cb.isTrue(root.get("isInScope")));
                    }
                    // "Still with us" — excludes leavers and people who never
                    // started, which is what anyone picking a manager means.
                    if ("true".equalsIgnoreCase(allParams.get("currentOnly"))) {
                        preds.add(cb.notEqual(root.get("status"), Personnel.Status.OFFBOARDED));
                    }
                    return preds;
                },
                (cb, root) -> {
                    Map<String, jakarta.persistence.criteria.Path<?>> f = new HashMap<>();
                    f.put("first_name",     root.get("firstName"));
                    f.put("firstname",      root.get("firstName"));
                    f.put("last_name",      root.get("lastName"));
                    f.put("lastname",       root.get("lastName"));
                    f.put("work_email",     root.get("workEmail"));
                    f.put("workemail",      root.get("workEmail"));
                    f.put("person_ref",     root.get("personRef"));
                    f.put("personref",      root.get("personRef"));
                    f.put("employeenumber", root.get("employeeNumber"));
                    f.put("jobtitle",       root.get("jobTitle"));
                    f.put("department",     root.get("department"));
                    f.put("status",         root.get("status"));
                    f.put("employmenttype", root.get("employmentType"));
                    f.put("startdate",      root.get("startDate"));
                    f.put("created_at",     root.get("createdAt"));
                    return f;
                },
                p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",             p.getId());
                    m.put("personRef",      p.getPersonRef());
                    m.put("firstName",      p.getFirstName());
                    m.put("lastName",       p.getLastName() != null ? p.getLastName() : "");
                    // Derived — there is no full_name column, and adding one
                    // would drift from its parts on the first rename.
                    m.put("fullName",       p.getFullName());
                    m.put("workEmail",      p.getWorkEmail() != null ? p.getWorkEmail() : "");
                    m.put("jobTitle",       p.getJobTitle() != null ? p.getJobTitle() : "");
                    m.put("department",     p.getDepartment() != null ? p.getDepartment() : "");
                    m.put("employmentType", p.getEmploymentType() != null ? p.getEmploymentType() : "");
                    m.put("status",         p.getStatus());
                    m.put("backgroundCheckStatus", p.getBackgroundCheckStatus());
                    m.put("sourceSystem",   p.getSourceSystem());
                    m.put("syncPaused",     p.isSyncPaused());
                    m.put("isInScope",      p.isInScope());
                    m.put("hasPrivilegedAccess", p.isHasPrivilegedAccess());
                    m.put("startDate",      p.getStartDate());
                    m.put("managerPersonnelId", p.getManagerPersonnelId());
                    m.put("managerName",    resolveManagerName(p.getManagerPersonnelId(), tenantId, nameCache));
                    // EntityTreeView reads parentId; the overview form binds
                    // managerPersonnelId. Both are emitted — one name would
                    // break one of the two.
                    m.put("parentId",       p.getManagerPersonnelId());
                    m.put("editable",       p.getStatus() != Personnel.Status.OFFBOARDED);
                    m.put("createdAt",      p.getCreatedAt());
                    m.put("updatedAt",      p.getUpdatedAt());
                    return m;
                });

        attachCompliance(page, tenantId);
        return ResponseEntity.ok(ApiResponse.success(page));
    }

    /** One exclusion query for the page, not one per person. */
    private void attachCompliance(PaginatedResponse<Map<String, Object>> page, Long tenantId) {
        List<Map<String, Object>> items = page.getItems();
        if (items == null || items.isEmpty()) return;

        List<Long> ids = items.stream().map(m -> (Long) m.get("id"))
                .filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return;

        List<Personnel> people = personnelRepository.findAllById(ids);
        Map<Long, Personnel.ComplianceStatus> byId =
                personnelService.computeComplianceBatch(people, tenantId);

        for (Map<String, Object> m : items) {
            Personnel.ComplianceStatus s = byId.get((Long) m.get("id"));
            m.put("complianceStatus", s != null ? s.name() : null);
        }
    }

    private String resolveManagerName(Long managerId, Long tenantId, Map<Long, String> cache) {
        if (managerId == null) return "";
        return cache.computeIfAbsent(managerId, mid ->
                personnelRepository.findByIdAndTenantIdAndIsDeletedFalse(mid, tenantId)
                        .map(Personnel::getFullName).orElse(""));
    }

    // ── Org chart ─────────────────────────────────────────────────────────────

    /**
     * The WHOLE reporting hierarchy, unpaginated.
     *
     * EntityTreeView previously built its tree from the paginated page, and
     * promoted any node whose parent was not in that page to a root:
     *
     *     if (!pid || !byId.has(pid)) roots.push(node)
     *
     * With take=20 and 45 people, that did not merely truncate the chart — it
     * drew a STRUCTURALLY WRONG one, showing people as top-level who report to
     * somebody on page 2, with no error anywhere.
     *
     * A hierarchy cannot be paginated and remain a hierarchy, so this returns
     * every row. Bounded by MAX_TREE_NODES: past that a tree is unreadable
     * anyway and the flat table is the better view, so the client is told to
     * fall back rather than being handed something it will render badly.
     */
    @GetMapping("/tree")
    @Operation(summary = "The full reporting hierarchy — unpaginated, for the org chart")
    public ResponseEntity<ApiResponse<Map<String, Object>>> tree(
            @RequestParam(required = false) Boolean includeOffboarded) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        boolean withLeavers = Boolean.TRUE.equals(includeOffboarded);

        // Through the service, not the repository. The filtering rule for "who is
        // in the org chart" belongs in one place, and a controller reaching past
        // the service for it is how two versions of that rule appear.
        List<Personnel> all = personnelService.listForTree(tenantId, withLeavers);

        Map<String, Object> out = new LinkedHashMap<>();
        if (all.size() > MAX_TREE_NODES) {
            // Honest refusal beats a chart nobody can read.
            out.put("truncated", true);
            out.put("total", all.size());
            out.put("limit", MAX_TREE_NODES);
            out.put("nodes", List.of());
            out.put("message", all.size() + " people is beyond what an org chart renders usefully. "
                    + "Use the list view, or filter by department first.");
            return ResponseEntity.ok(ApiResponse.success(out));
        }

        Map<Long, String> nameCache = new HashMap<>();
        Map<Long, Long> childCounts = new HashMap<>();
        for (Personnel p : all) {
            if (p.getManagerPersonnelId() != null) {
                childCounts.merge(p.getManagerPersonnelId(), 1L, Long::sum);
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>(all.size());
        for (Personnel p : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",             p.getId());
            m.put("personRef",      p.getPersonRef());
            m.put("fullName",       p.getFullName());
            m.put("jobTitle",       p.getJobTitle() != null ? p.getJobTitle() : "");
            m.put("department",     p.getDepartment() != null ? p.getDepartment() : "");
            m.put("employmentType", p.getEmploymentType());
            m.put("status",         p.getStatus());
            m.put("backgroundCheckStatus", p.getBackgroundCheckStatus());
            m.put("hasPrivilegedAccess",   p.isHasPrivilegedAccess());
            m.put("isInScope",      p.isInScope());
            // parentId is what the tree builds on; managerPersonnelId is what
            // the form field binds to. Both, as on the list.
            m.put("parentId",           p.getManagerPersonnelId());
            m.put("managerPersonnelId", p.getManagerPersonnelId());
            m.put("managerName",    resolveManagerName(p.getManagerPersonnelId(), tenantId, nameCache));
            m.put("directReportCount", childCounts.getOrDefault(p.getId(), 0L).intValue());
            m.put("editable",       p.getStatus() != Personnel.Status.OFFBOARDED);
            nodes.add(m);
        }

        out.put("truncated", false);
        out.put("total", nodes.size());
        out.put("nodes", nodes);
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    /**
     * Above this, an org chart stops being a chart. The flat table sorts,
     * filters and paginates; a 500-node tree does none of those well.
     */
    private static final int MAX_TREE_NODES = 500;

    // ── Read ──────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(summary = "Get one person with their compliance rollup and exclusions")
    public ResponseEntity<ApiResponse<PersonnelResponse>> getById(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(personnelService.getById(id, tenantId)));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    @Operation(summary = "Update a person — identity, role, reporting line, scope, sync settings")
    public ResponseEntity<ApiResponse<PersonnelResponse>> update(
            @PathVariable Long id, @Valid @RequestBody PersonnelRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.update(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PutMapping("/{id}/employment")
    @Operation(summary = "Save the employment tab — dates, separation reason and offboarding evidence")
    public ResponseEntity<ApiResponse<PersonnelResponse>> saveEmployment(
            @PathVariable Long id, @Valid @RequestBody PersonnelEmploymentRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.saveEmployment(id, req, ctx.getId(), ctx.getTenantId())));
    }

    /**
     * The only endpoint that writes screening fields, which is what makes the
     * separate personnel:screen permission on it meaningful rather than
     * decorative.
     */
    @PutMapping("/{id}/screening")
    @Operation(summary = "Save the screening tab — background check outcome and agreements")
    public ResponseEntity<ApiResponse<PersonnelResponse>> saveScreening(
            @PathVariable Long id, @Valid @RequestBody PersonnelScreeningRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.saveScreening(id, req, ctx.getId(), ctx.getTenantId())));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/activate")
    @Operation(summary = "PENDING_START to ACTIVE")
    public ResponseEntity<ApiResponse<PersonnelResponse>> activate(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.activate(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/never-started")
    @Operation(summary = "Offer withdrawn — straight to OFFBOARDED, keeping the record")
    public ResponseEntity<ApiResponse<PersonnelResponse>> neverStarted(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.neverStarted(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/start-leave")
    @Operation(summary = "ACTIVE to ON_LEAVE")
    public ResponseEntity<ApiResponse<PersonnelResponse>> startLeave(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.startLeave(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/return-leave")
    @Operation(summary = "ON_LEAVE back to ACTIVE")
    public ResponseEntity<ApiResponse<PersonnelResponse>> returnLeave(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.returnFromLeave(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/give-notice")
    @Operation(summary = "ACTIVE to NOTICE_PERIOD")
    public ResponseEntity<ApiResponse<PersonnelResponse>> giveNotice(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.giveNotice(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/start-offboarding")
    @Operation(summary = "Begin offboarding")
    public ResponseEntity<ApiResponse<PersonnelResponse>> startOffboarding(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.startOffboarding(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/complete-offboarding")
    @Operation(summary = "Complete offboarding — refuses on unreturned assets or missing revocation evidence")
    public ResponseEntity<ApiResponse<PersonnelResponse>> completeOffboarding(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.completeOffboarding(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    /** UniversalModulePage posts requires_remarks text as "remarks". */
    private String remarksOf(Map<String, Object> body) {
        if (body == null) return null;
        Object v = body.get("remarks");
        return v != null ? String.valueOf(v) : null;
    }

    // ── Exclusions ────────────────────────────────────────────────────────────

    @GetMapping("/{id}/linked-exclusions")
    @Operation(summary = "Compliance carve-outs for this person")
    public ResponseEntity<ApiResponse<List<PersonnelResponse.Exclusion>>> listExclusions(
            @PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(personnelService.listExclusions(id, tenantId)));
    }

    @PostMapping("/{id}/exclusions")
    @Operation(summary = "Exclude this person from a compliance requirement, with a reason")
    public ResponseEntity<ApiResponse<PersonnelResponse.Exclusion>> addExclusion(
            @PathVariable Long id, @Valid @RequestBody PersonnelExclusionRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                personnelService.addExclusion(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @DeleteMapping("/{id}/exclusions/{exclusionId}")
    @Operation(summary = "Revoke an exclusion — deactivated, not deleted")
    public ResponseEntity<ApiResponse<Void>> revokeExclusion(
            @PathVariable Long id, @PathVariable Long exclusionId) {
        User ctx = utilityService.getLoggedInDataContext();
        personnelService.revokeExclusion(id, exclusionId, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Asset custody ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/linked-assets")
    @Operation(summary = "Assets issued to this person and whether they came back")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> linkedAssets(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(personnelService.listLinkedAssets(id, tenantId)));
    }

    @PostMapping("/{id}/assets")
    @Operation(summary = "Record an asset issued to this person")
    public ResponseEntity<ApiResponse<Void>> assignAsset(
            @PathVariable Long id, @Valid @RequestBody PersonnelAssetRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        personnelService.assignAsset(id, req, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
    }

    /** Records the return. The row stays — that it was issued is part of the record. */
    @DeleteMapping("/{id}/assets/{assetId}")
    @Operation(summary = "Record the return of an issued asset")
    public ResponseEntity<ApiResponse<Void>> returnAsset(
            @PathVariable Long id, @PathVariable Long assetId) {
        User ctx = utilityService.getLoggedInDataContext();
        personnelService.returnAsset(id, assetId, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Pull users onto the roster ────────────────────────────────────────────

    /**
     * Creates a roster row for every user of this tenant that lacks one.
     *
     * An endpoint rather than a migration because it is not a one-off: most
     * accounts predate this module, and more arrive every time somebody is
     * invited. Idempotent, so it is safe to press whenever.
     *
     * Users whose email matches an existing UNLINKED roster row are reported,
     * not merged — see PersonnelService.pullUsersOntoRoster for why guessing
     * there is the wrong call.
     */
    @PostMapping("/pull-users")
    @Operation(summary = "Add a roster record for every user who lacks one")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pullUsers() {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                personnelService.pullUsersOntoRoster(ctx.getTenantId(), ctx.getId())));
    }

    // ── Soft delete ───────────────────────────────────────────────────────────

    /**
     * Soft delete, for records created in error only.
     *
     * The service refuses anyone with an employment history: their offboarding
     * record is the audit artifact. A reason is mandatory and stored with who
     * removed it and when, following FeatureFlag — the one other entity in this
     * codebase that treats removal as a fact worth keeping.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a record created in error — refuses anyone who has ever been active")
    public ResponseEntity<ApiResponse<Void>> softDelete(
            @PathVariable Long id, @Valid @RequestBody PersonnelDeleteRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        personnelService.softDelete(id, req.getReason(), ctx.getId(), ctx.getTenantId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    @Operation(summary = "Roster stats — headcount, screening gaps, offboarding evidence gaps, assets outstanding")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(personnelService.getStats(tenantId)));
    }
}