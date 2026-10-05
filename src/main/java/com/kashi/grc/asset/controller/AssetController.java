package com.kashi.grc.asset.controller;

import com.kashi.grc.asset.domain.Asset;
import com.kashi.grc.asset.dto.*;
import com.kashi.grc.asset.service.AssetService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
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
 * Asset inventory API — ISO 27001 A.5.9.
 *
 * ── THE ENDPOINT LIST IS NOT FREE-FORM ────────────────────────────────────
 * Every path below is named by a row in 07_asset_inventory_seed.sql. Renaming
 * one here breaks a button with no error on either side:
 *
 *   ui_forms.submit_url
 *     POST /v1/assets                     asset_create_form
 *     PUT  /v1/assets/{id}                asset_detail_header, _tab_overview
 *     PUT  /v1/assets/{id}/valuation      asset_detail_tab_valuation
 *     PUT  /v1/assets/{id}/lifecycle      asset_detail_tab_lifecycle
 *
 *   ui_actions.api_endpoint
 *     POST /v1/assets/{id}/send-repair    ASSET_SEND_REPAIR
 *     POST /v1/assets/{id}/return-service ASSET_RETURN_SERVICE
 *     POST /v1/assets/{id}/store          ASSET_STORE
 *     POST /v1/assets/{id}/retire         ASSET_RETIRE
 *     POST /v1/assets/{id}/reinstate      ASSET_REINSTATE
 *     POST /v1/assets/{id}/dispose        ASSET_DISPOSE
 *     POST /v1/assets/{id}/risks          ASSET_LINK_RISK
 *
 *   ui_layouts.tabs_json
 *     GET  /v1/assets/{id}/linked-risks   tab key "linked-risks", served by the
 *                                         generic LinkedEntitiesTab
 *
 * No @PreAuthorize, matching every other module controller here.
 */
@Slf4j
@RestController
@RequestMapping("/v1/assets")
@Tag(name = "Asset Inventory", description = "ISO 27001 A.5.9 — inventory of information and associated assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService   assetService;
    private final UtilityService utilityService;
    private final UserRepository userRepository;
    private final DbRepository   dbRepository;

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Add an asset to the inventory")
    public ResponseEntity<ApiResponse<AssetResponse>> create(@Valid @RequestBody AssetRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        AssetResponse response = assetService.create(req, ctx.getId(), ctx.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // ── List ──────────────────────────────────────────────────────────────────

    /**
     * Mapped inline rather than through AssetService.toResponse so DbRepository's
     * pagination stays on the query — building full detail payloads per row
     * would fire a linked-risk lookup and a child-count query per asset.
     *
     * `parentId` is emitted alongside `parentAssetId` on purpose: EntityTreeView
     * builds the hierarchy from parentId (blueprint supports_tree = 1), while
     * the overview form binds a field called parentAssetId. One name would
     * break one of the two.
     */
    @GetMapping
    @Operation(summary = "List assets — filter by status, assetType, criticality, environment, classification")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> list(
            @RequestParam Map<String, String> allParams) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Map<Long, String> nameCache = new HashMap<>();

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                Asset.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
                    preds.add(cb.equal(root.get("tenantId"), tenantId));
                    preds.add(cb.isFalse(root.get("isDeleted")));

                    if (allParams.containsKey("status")) {
                        preds.add(cb.equal(root.get("status"),
                                Asset.Status.valueOf(allParams.get("status").toUpperCase())));
                    }
                    if (allParams.containsKey("assetType")) {
                        preds.add(cb.equal(root.get("assetType"), allParams.get("assetType").toUpperCase()));
                    }
                    if (allParams.containsKey("assetClass")) {
                        preds.add(cb.equal(root.get("assetClass"), allParams.get("assetClass").toUpperCase()));
                    }
                    if (allParams.containsKey("criticality")) {
                        preds.add(cb.equal(root.get("criticality"), allParams.get("criticality").toUpperCase()));
                    }
                    if (allParams.containsKey("environment")) {
                        preds.add(cb.equal(root.get("environment"), allParams.get("environment").toUpperCase()));
                    }
                    if (allParams.containsKey("classification")) {
                        preds.add(cb.equal(root.get("classification"), allParams.get("classification").toUpperCase()));
                    }
                    if (allParams.containsKey("ownerId")) {
                        preds.add(cb.equal(root.get("ownerId"), Long.parseLong(allParams.get("ownerId"))));
                    }
                    if (allParams.containsKey("vendorId")) {
                        preds.add(cb.equal(root.get("vendorId"), Long.parseLong(allParams.get("vendorId"))));
                    }
                    if (allParams.containsKey("parentAssetId")) {
                        preds.add(cb.equal(root.get("parentAssetId"),
                                Long.parseLong(allParams.get("parentAssetId"))));
                    }
                    // Asks the one question the module exists to answer cheaply.
                    if ("true".equalsIgnoreCase(allParams.get("containsPersonalData"))) {
                        preds.add(cb.isTrue(root.get("containsPersonalData")));
                    }
                    return preds;
                },
                // filterBy and sortBy resolve against these keys only —
                // a key absent here is silently ignored by DbRepository, so
                // every list-layout column that should sort must appear.
                (cb, root) -> {
                    Map<String, jakarta.persistence.criteria.Path<?>> fields = new HashMap<>();
                    fields.put("name",           root.get("name"));
                    fields.put("asset_ref",      root.get("assetRef"));
                    fields.put("assetref",       root.get("assetRef"));
                    fields.put("assettype",      root.get("assetType"));
                    fields.put("assetclass",     root.get("assetClass"));
                    fields.put("criticality",    root.get("criticality"));
                    fields.put("classification", root.get("classification"));
                    fields.put("environment",    root.get("environment"));
                    fields.put("status",         root.get("status"));
                    fields.put("location",       root.get("location"));
                    fields.put("serialnumber",   root.get("serialNumber"));
                    fields.put("nextreviewdate", root.get("nextReviewDate"));
                    fields.put("endoflifedate",  root.get("endOfLifeDate"));
                    fields.put("created_at",     root.get("createdAt"));
                    return fields;
                },
                a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",             a.getId());
                    m.put("assetRef",       a.getAssetRef());
                    m.put("name",           a.getName());
                    m.put("assetType",      a.getAssetType()      != null ? a.getAssetType()      : "");
                    m.put("assetClass",     a.getAssetClass()     != null ? a.getAssetClass()     : "");
                    m.put("criticality",    a.getCriticality()    != null ? a.getCriticality()    : "");
                    m.put("classification", a.getClassification() != null ? a.getClassification() : "");
                    m.put("environment",    a.getEnvironment()    != null ? a.getEnvironment()    : "");
                    m.put("hostingModel",   a.getHostingModel()   != null ? a.getHostingModel()   : "");
                    m.put("status",         a.getStatus());
                    m.put("ownerId",        a.getOwnerId());
                    m.put("ownerName",      resolveName(a.getOwnerId(), nameCache));
                    m.put("location",       a.getLocation() != null ? a.getLocation() : "");
                    m.put("nextReviewDate", a.getNextReviewDate());
                    m.put("endOfLifeDate",  a.getEndOfLifeDate());
                    m.put("containsPersonalData", a.isContainsPersonalData());
                    // Tree: EntityTreeView reads parentId. parentAssetId is kept
                    // for anything binding the form field of that name.
                    m.put("parentId",       a.getParentAssetId());
                    m.put("parentAssetId",  a.getParentAssetId());
                    // editable = false makes UniversalModulePage drop the
                    // collaboration tabs on a disposed asset.
                    m.put("editable",       a.getStatus() != Asset.Status.DISPOSED);
                    m.put("createdAt",      a.getCreatedAt());
                    m.put("updatedAt",      a.getUpdatedAt());
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

    /**
     * The WHOLE composition hierarchy, unpaginated.
     *
     * Same reason as the personnel org chart: EntityTreeView built its tree from
     * the paginated page and promoted any node whose parent was absent to a
     * root, so a facility's servers appeared as top-level assets the moment the
     * inventory exceeded one page. Wrong structure, no error.
     *
     * A hierarchy cannot be paginated and stay a hierarchy. Bounded at
     * MAX_TREE_NODES, past which the flat table is genuinely the better view
     * and the client is told to use it.
     */
    @GetMapping("/tree")
    @Operation(summary = "The full asset composition hierarchy — unpaginated")
    public ResponseEntity<ApiResponse<Map<String, Object>>> tree(
            @RequestParam(required = false) Boolean includeDisposed) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        boolean withDisposed = Boolean.TRUE.equals(includeDisposed);

        List<Asset> all = assetService.listAllForTree(tenantId, withDisposed);

        Map<String, Object> out = new LinkedHashMap<>();
        if (all.size() > MAX_TREE_NODES) {
            out.put("truncated", true);
            out.put("total", all.size());
            out.put("limit", MAX_TREE_NODES);
            out.put("nodes", List.of());
            out.put("message", all.size() + " assets is beyond what a hierarchy renders usefully. "
                    + "Use the list view, or filter by type or environment first.");
            return ResponseEntity.ok(ApiResponse.success(out));
        }

        Map<Long, Long> childCounts = new HashMap<>();
        for (Asset a : all) {
            if (a.getParentAssetId() != null) childCounts.merge(a.getParentAssetId(), 1L, Long::sum);
        }

        List<Map<String, Object>> nodes = new ArrayList<>(all.size());
        for (Asset a : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",            a.getId());
            m.put("assetRef",      a.getAssetRef());
            m.put("name",          a.getName());
            m.put("assetType",     a.getAssetType() != null ? a.getAssetType() : "");
            m.put("assetClass",    a.getAssetClass() != null ? a.getAssetClass() : "");
            m.put("status",        a.getStatus());
            m.put("criticality",   a.getCriticality());
            m.put("classification", a.getClassification());
            m.put("environment",   a.getEnvironment());
            m.put("containsPersonalData", a.isContainsPersonalData());
            // parentId for the tree, parentAssetId for the form field.
            m.put("parentId",      a.getParentAssetId());
            m.put("parentAssetId", a.getParentAssetId());
            m.put("childCount",    childCounts.getOrDefault(a.getId(), 0L).intValue());
            m.put("editable",      a.getStatus() != Asset.Status.DISPOSED);
            nodes.add(m);
        }

        out.put("truncated", false);
        out.put("total", nodes.size());
        out.put("nodes", nodes);
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    /** Above this a hierarchy stops being readable; the flat table is better. */
    private static final int MAX_TREE_NODES = 500;

    @GetMapping("/{id}")
    @Operation(summary = "Get one asset with its linked risks and child count")
    public ResponseEntity<ApiResponse<AssetResponse>> getById(@PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(assetService.getById(id, tenantId)));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    @Operation(summary = "Update an asset — identity, ownership, placement, cross-links")
    public ResponseEntity<ApiResponse<AssetResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody AssetRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assetService.update(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PutMapping("/{id}/valuation")
    @Operation(summary = "Save the valuation tab — criticality is recalculated from C/I/A")
    public ResponseEntity<ApiResponse<AssetResponse>> saveValuation(
            @PathVariable Long id,
            @Valid @RequestBody AssetValuationRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assetService.saveValuation(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @PutMapping("/{id}/lifecycle")
    @Operation(summary = "Save the lifecycle tab — acquisition, EOL, review, disposal details")
    public ResponseEntity<ApiResponse<AssetResponse>> saveLifecycle(
            @PathVariable Long id,
            @Valid @RequestBody AssetLifecycleRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assetService.saveLifecycle(id, req, ctx.getId(), ctx.getTenantId())));
    }

    // ── Lifecycle transitions ─────────────────────────────────────────────────

    @PostMapping("/{id}/send-repair")
    @Operation(summary = "ACTIVE to IN_REPAIR")
    public ResponseEntity<ApiResponse<AssetResponse>> sendForRepair(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assetService.sendForRepair(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/store")
    @Operation(summary = "ACTIVE to IN_STORAGE")
    public ResponseEntity<ApiResponse<AssetResponse>> moveToStorage(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assetService.moveToStorage(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/return-service")
    @Operation(summary = "IN_REPAIR or IN_STORAGE back to ACTIVE")
    public ResponseEntity<ApiResponse<AssetResponse>> returnToService(@PathVariable Long id) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assetService.returnToService(id, ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/retire")
    @Operation(summary = "Retire from service — refuses while live child assets sit on it")
    public ResponseEntity<ApiResponse<AssetResponse>> retire(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assetService.retire(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/reinstate")
    @Operation(summary = "RETIRED back to ACTIVE")
    public ResponseEntity<ApiResponse<AssetResponse>> reinstate(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assetService.reinstate(id, remarksOf(body), ctx.getId(), ctx.getTenantId())));
    }

    @PostMapping("/{id}/dispose")
    @Operation(summary = "Record final disposal — requires a method and an evidence reference")
    public ResponseEntity<ApiResponse<AssetResponse>> dispose(
            @PathVariable Long id,
            @RequestBody(required = false) AssetLifecycleRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.ok(ApiResponse.success(
                assetService.dispose(id, req, ctx.getId(), ctx.getTenantId())));
    }

    /** UniversalModulePage posts requires_remarks text as "remarks". */
    private String remarksOf(Map<String, Object> body) {
        if (body == null) return null;
        Object v = body.get("remarks");
        return v != null ? String.valueOf(v) : null;
    }

    // ── Linked risks ──────────────────────────────────────────────────────────

    /**
     * Served to the generic LinkedEntitiesTab, which derives this path from the
     * tab key: tabs_json key "linked-risks" -> {apiBasePath}/{id}/linked-risks.
     * The plain /risks alias below is what the ASSET_LINK_RISK action posts to.
     */
    @GetMapping("/{id}/linked-risks")
    @Operation(summary = "Risks this asset is exposed to")
    public ResponseEntity<ApiResponse<List<AssetResponse.LinkedRisk>>> listLinkedRisks(
            @PathVariable Long id) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                assetService.listLinkedRisks(id, tenantId)));
    }

    @GetMapping("/{id}/risks")
    @Operation(summary = "Alias of /linked-risks")
    public ResponseEntity<ApiResponse<List<AssetResponse.LinkedRisk>>> listRisks(@PathVariable Long id) {
        return listLinkedRisks(id);
    }

    @PostMapping("/{id}/risks")
    @Operation(summary = "Link a risk this asset is exposed to")
    public ResponseEntity<ApiResponse<AssetResponse.LinkedRisk>> linkRisk(
            @PathVariable Long id,
            @Valid @RequestBody AssetRiskLinkRequest req) {
        User ctx = utilityService.getLoggedInDataContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                assetService.linkRisk(id, req, ctx.getId(), ctx.getTenantId())));
    }

    @DeleteMapping("/{id}/risks/{riskId}")
    @Operation(summary = "Unlink a risk from this asset")
    public ResponseEntity<ApiResponse<Void>> unlinkRisk(
            @PathVariable Long id,
            @PathVariable Long riskId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        assetService.unlinkRisk(id, riskId, tenantId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    @Operation(summary = "Inventory stats — counts by status, type and criticality, EOL and review-overdue counts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(assetService.getStats(tenantId)));
    }
}