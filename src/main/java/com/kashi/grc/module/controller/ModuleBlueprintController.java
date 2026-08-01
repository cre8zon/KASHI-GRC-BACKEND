package com.kashi.grc.module.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.uiconfig.repository.FeatureFlagRepository;
import com.kashi.grc.uiconfig.domain.FeatureFlag;
import org.springframework.http.HttpStatus;
import java.util.Set;
import java.util.stream.Collectors;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.module.domain.ModuleBlueprint;
import com.kashi.grc.module.repository.ModuleBlueprintRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ModuleBlueprintController — zero-code module definition management.
 *
 * Each blueprint defines a complete GRC module (RISK, AUDIT, ISSUE, POLICY, CONTROL, etc.)
 * that the Universal Module Page at /module/:entityType renders without any code deployment.
 *
 * Platform Admin only (SYSTEM side — enforced by SecurityConfig).
 *
 * GET    /v1/admin/module-blueprints                    — list (paginated)
 * POST   /v1/admin/module-blueprints                    — create
 * GET    /v1/admin/module-blueprints/{id}               — get by ID
 * GET    /v1/admin/module-blueprints/by-type/{entityType} — get by entityType (used by Universal Module Page)
 * PUT    /v1/admin/module-blueprints/{id}               — update
 * DELETE /v1/admin/module-blueprints/{id}               — delete
 * PUT    /v1/admin/module-blueprints/{id}/activate      — publish
 * PUT    /v1/admin/module-blueprints/{id}/deactivate    — unpublish
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/module-blueprints")
@Tag(name = "Module Blueprints (Platform Admin)", description = "Zero-code GRC module definitions")
@RequiredArgsConstructor
public class ModuleBlueprintController {

    private final ModuleBlueprintRepository blueprintRepository;
    private final DbRepository              dbRepository;
    private final UtilityService            utilityService;
    private final FeatureFlagRepository      featureFlagRepository;

    // ══════════════════════════════════════════════════════════════
    // LIST
    // ══════════════════════════════════════════════════════════════

    @GetMapping
    @Operation(summary = "List module blueprints — paginated, search by entityType or displayName")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> list(
            @RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        String search = allParams.getOrDefault("search", "");

        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                ModuleBlueprint.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
                    // Global (tenantId null) OR tenant-specific
                    p.add(cb.or(
                            cb.isNull(root.get("tenantId")),
                            cb.equal(root.get("tenantId"), tenantId)));
                    if (!search.isBlank()) {
                        p.add(cb.or(
                                cb.like(cb.lower(root.get("entityType")),  "%" + search.toLowerCase() + "%"),
                                cb.like(cb.lower(root.get("displayName")), "%" + search.toLowerCase() + "%")));
                    }
                    return p;
                },
                (cb, root) -> Map.of("sortorder", root.get("sortOrder"), "displayname", root.get("displayName")),
                this::toDetailMap)));
    }

    // ══════════════════════════════════════════════════════════════
    // GET BY ID
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/{id}")
    @Operation(summary = "Get a module blueprint by ID")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable Long id) {
        ModuleBlueprint bp = blueprintRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ModuleBlueprint", id));
        return ResponseEntity.ok(ApiResponse.success(toDetailMap(bp)));
    }

    // ══════════════════════════════════════════════════════════════
    // GET BY ENTITY TYPE — used by Universal Module Page at runtime
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/by-type/{entityType}")
    @Operation(summary = "Get active module blueprint by entityType — consumed by Universal Module Page")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getByType(
            @PathVariable String entityType) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // Tenant-specific blueprint takes precedence over global (tenantId = null)
        ModuleBlueprint bp = blueprintRepository
                .findByEntityTypeIgnoreCaseAndTenantId(entityType.toUpperCase(), tenantId)
                .or(() -> blueprintRepository.findByEntityTypeIgnoreCaseAndTenantIdIsNull(entityType.toUpperCase()))
                .orElseThrow(() -> new ResourceNotFoundException("ModuleBlueprint", "entityType", entityType));

        // Feature entitlement gate: if this module requires a feature the tenant
        // doesn't have, block the whole screen (not just its data). The frontend
        // catches this 403 (FEATURE_NOT_LICENSED) and redirects.
        if (bp.getRequiredFeature() != null && !bp.getRequiredFeature().isBlank()) {
            Set<String> tenantFeatures = featureFlagRepository.resolveEnabledFeaturesForTenant(tenantId);
            if (!tenantFeatures.contains(bp.getRequiredFeature())) {
                throw new BusinessException("FEATURE_NOT_LICENSED",
                        "This module is not enabled for your organization.", HttpStatus.FORBIDDEN);
            }
        }

        return ResponseEntity.ok(ApiResponse.success(toDetailMap(bp)));
    }

    // ══════════════════════════════════════════════════════════════
    // CREATE
    // ══════════════════════════════════════════════════════════════

    @PostMapping
    @Operation(summary = "Create a module blueprint — starts as inactive (draft)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @Valid @RequestBody ModuleBlueprintRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        ModuleBlueprint bp = ModuleBlueprint.builder()
                .entityType(req.getEntityType().toUpperCase().replace(" ", "_"))
                .displayName(req.getDisplayName())
                .displayNamePlural(req.getDisplayNamePlural())
                .icon(req.getIcon() != null ? req.getIcon() : "Layers")
                .colorTag(req.getColorTag() != null ? req.getColorTag() : "blue")
                .fieldsSchemaJson(req.getFieldsSchemaJson())
                .statusFlowJson(req.getStatusFlowJson())
                .workflowEligibility(req.getWorkflowEligibility())
                .listScreenKey(req.getListScreenKey())
                .detailScreenKey(req.getDetailScreenKey())
                .createFormKey(req.getCreateFormKey())
                .editFormKey(req.getEditFormKey())
                .apiBasePath(req.getApiBasePath())
                .supportsActionItems(req.isSupportsActionItems())
                .supportsDocuments(req.isSupportsDocuments())
                .supportsComments(req.isSupportsComments())
                .supportsWorkflow(req.isSupportsWorkflow())
                .showInNav(req.isShowInNav())
                .allowedSides(req.getAllowedSides() != null ? req.getAllowedSides() : "ORGANIZATION,SYSTEM")
                .requiredFeature(req.getRequiredFeature())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                // ── v2 new fields ──
                .supportsTree(req.isSupportsTree())
                .parentContextJson(req.getParentContextJson())
                .wsTopicPattern(req.getWsTopicPattern())
                .navKey(req.getNavKey() != null && !req.getNavKey().isBlank() ? req.getNavKey() : null)
                .isActive(false)  // always starts as draft
                .tenantId(tenantId)
                .build();

        blueprintRepository.save(bp);
        log.info("[MODULE-BP] Created entityType={} tenantId={}", bp.getEntityType(), tenantId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toSimpleMap("id", bp.getId(), "entityType", bp.getEntityType())));
    }

    // ══════════════════════════════════════════════════════════════
    // UPDATE
    // ══════════════════════════════════════════════════════════════

    @PutMapping("/{id}")
    @Operation(summary = "Update a module blueprint — entityType is immutable after creation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable Long id, @RequestBody ModuleBlueprintRequest req) {
        ModuleBlueprint bp = blueprintRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ModuleBlueprint", id));

        // entityType is immutable — never update it
        if (req.getDisplayName()        != null) bp.setDisplayName(req.getDisplayName());
        if (req.getDisplayNamePlural()  != null) bp.setDisplayNamePlural(req.getDisplayNamePlural());
        if (req.getIcon()               != null) bp.setIcon(req.getIcon());
        if (req.getColorTag()           != null) bp.setColorTag(req.getColorTag());
        if (req.getFieldsSchemaJson()   != null) bp.setFieldsSchemaJson(req.getFieldsSchemaJson());
        if (req.getStatusFlowJson()     != null) bp.setStatusFlowJson(req.getStatusFlowJson());
        if (req.getWorkflowEligibility()!= null) bp.setWorkflowEligibility(req.getWorkflowEligibility());
        if (req.getListScreenKey()      != null) bp.setListScreenKey(req.getListScreenKey());
        if (req.getDetailScreenKey()    != null) bp.setDetailScreenKey(req.getDetailScreenKey());
        if (req.getCreateFormKey()      != null) bp.setCreateFormKey(req.getCreateFormKey());
        if (req.getEditFormKey()        != null) bp.setEditFormKey(req.getEditFormKey());
        if (req.getApiBasePath()        != null) bp.setApiBasePath(req.getApiBasePath());
        if (req.getAllowedSides()       != null) bp.setAllowedSides(req.getAllowedSides());
        if (req.getRequiredFeature()    != null) bp.setRequiredFeature(req.getRequiredFeature().isBlank() ? null : req.getRequiredFeature());
        if (req.getSortOrder()          != null) bp.setSortOrder(req.getSortOrder());
        // ── v2 new fields — null means "don't change", empty string means "clear"
        if (req.getParentContextJson()  != null) bp.setParentContextJson(
                req.getParentContextJson().isBlank() ? null : req.getParentContextJson());
        if (req.getWsTopicPattern()     != null) bp.setWsTopicPattern(
                req.getWsTopicPattern().isBlank() ? null : req.getWsTopicPattern());
        if (req.getNavKey()              != null) bp.setNavKey(
                req.getNavKey().isBlank() ? null : req.getNavKey());
        // Boolean capabilities always updated (false is a valid value)
        bp.setSupportsActionItems(req.isSupportsActionItems());
        bp.setSupportsDocuments(req.isSupportsDocuments());
        bp.setSupportsComments(req.isSupportsComments());
        bp.setSupportsWorkflow(req.isSupportsWorkflow());
        bp.setSupportsTree(req.isSupportsTree());   // v2
        bp.setShowInNav(req.isShowInNav());

        blueprintRepository.save(bp);
        return ResponseEntity.ok(ApiResponse.success(
                toSimpleMap("id", bp.getId(), "entityType", bp.getEntityType())));
    }

    // ══════════════════════════════════════════════════════════════
    // ACTIVATE / DEACTIVATE
    // ══════════════════════════════════════════════════════════════

    @PutMapping("/{id}/activate")
    @Operation(summary = "Publish a module blueprint — makes it available to the Universal Module Page")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activate(@PathVariable Long id) {
        ModuleBlueprint bp = blueprintRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ModuleBlueprint", id));
        bp.setActive(true);
        blueprintRepository.save(bp);
        log.info("[MODULE-BP] Activated entityType={}", bp.getEntityType());
        return ResponseEntity.ok(ApiResponse.success(toSimpleMap("id", bp.getId(), "isActive", true)));
    }

    @PutMapping("/{id}/deactivate")
    @Operation(summary = "Unpublish a module blueprint — Universal Module Page will 404 for this entityType")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deactivate(@PathVariable Long id) {
        ModuleBlueprint bp = blueprintRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ModuleBlueprint", id));
        bp.setActive(false);
        blueprintRepository.save(bp);
        log.info("[MODULE-BP] Deactivated entityType={}", bp.getEntityType());
        return ResponseEntity.ok(ApiResponse.success(toSimpleMap("id", bp.getId(), "isActive", false)));
    }

    // ══════════════════════════════════════════════════════════════
    // DELETE
    // ══════════════════════════════════════════════════════════════

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a module blueprint — existing records of this entityType are unaffected")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        blueprintRepository.deleteById(id);
        log.info("[MODULE-BP] Deleted id={}", id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> toDetailMap(ModuleBlueprint bp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                  bp.getId());
        m.put("entityType",          bp.getEntityType());
        m.put("displayName",         bp.getDisplayName());
        m.put("displayNamePlural",   bp.getDisplayNamePlural() != null ? bp.getDisplayNamePlural() : "");
        m.put("icon",                bp.getIcon() != null ? bp.getIcon() : "Layers");
        m.put("colorTag",            bp.getColorTag() != null ? bp.getColorTag() : "blue");
        m.put("fieldsSchemaJson",    bp.getFieldsSchemaJson());
        m.put("statusFlowJson",      bp.getStatusFlowJson());
        m.put("workflowEligibility", bp.getWorkflowEligibility() != null ? bp.getWorkflowEligibility() : "");
        m.put("listScreenKey",       bp.getListScreenKey() != null ? bp.getListScreenKey() : "");
        m.put("detailScreenKey",     bp.getDetailScreenKey() != null ? bp.getDetailScreenKey() : "");
        m.put("createFormKey",       bp.getCreateFormKey() != null ? bp.getCreateFormKey() : "");
        m.put("editFormKey",         bp.getEditFormKey() != null ? bp.getEditFormKey() : "");
        m.put("apiBasePath",         bp.getApiBasePath() != null ? bp.getApiBasePath() : "");
        m.put("supportsActionItems", bp.isSupportsActionItems());
        m.put("supportsDocuments",   bp.isSupportsDocuments());
        m.put("supportsComments",    bp.isSupportsComments());
        m.put("supportsWorkflow",    bp.isSupportsWorkflow());
        m.put("showInNav",           bp.isShowInNav());
        m.put("supportsTree",        bp.isSupportsTree());          // v2
        m.put("allowedSides",        bp.getAllowedSides() != null ? bp.getAllowedSides() : "ORGANIZATION,SYSTEM");
        m.put("requiredFeature",     bp.getRequiredFeature());
        m.put("sortOrder",           bp.getSortOrder() != null ? bp.getSortOrder() : 0);
        m.put("isActive",            bp.isActive());
        // v2 new fields — always included (null if not set, frontend must handle)
        m.put("parentContextJson",   bp.getParentContextJson());
        m.put("wsTopicPattern",      bp.getWsTopicPattern());
        m.put("navKey",              bp.getNavKey() != null ? bp.getNavKey() : "");
        m.put("tenantId",            bp.getTenantId());
        m.put("createdAt",           bp.getCreatedAt());
        m.put("updatedAt",           bp.getUpdatedAt());
        return m;
    }

    private Map<String, Object> toSimpleMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2) m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }

    // ── Inner request DTO ─────────────────────────────────────────────────────

    @Data
    public static class ModuleBlueprintRequest {
        @NotBlank private String  entityType;        // RISK, AUDIT, ISSUE — UPPER_SNAKE_CASE
        @NotBlank private String  displayName;       // "Risk Management"
        private String            displayNamePlural; // "Risks"
        private String            icon;              // Lucide icon name — default Layers
        private String            colorTag;          // blue | green | amber | red | purple | teal | gray
        private String            fieldsSchemaJson;  // JSON Schema for entity fields
        private String            statusFlowJson;    // Status transitions JSON
        private String            workflowEligibility; // comma-sep workflow entityTypes
        private String            listScreenKey;     // screen config key for list page
        private String            detailScreenKey;   // screen config key for detail page
        private String            createFormKey;     // form key for create modal
        private String            editFormKey;       // form key for edit modal (fallback to createFormKey)
        @NotBlank private String  apiBasePath;       // e.g. /v1/risks
        private boolean           supportsActionItems = true;
        private boolean           supportsDocuments   = true;
        private boolean           supportsComments    = true;
        private boolean           supportsWorkflow    = true;
        private boolean           showInNav           = true;
        private boolean           supportsTree        = false;       // v2 — renders tree instead of flat DataTable
        private String            allowedSides;      // comma-sep sides: ORGANIZATION,VENDOR,AUDITOR
        private String            requiredFeature;   // feature_flags key; blocks the module for tenants without it
        private Integer           sortOrder;
        // ── v2 new fields ─────────────────────────────────────────────────────────
        /**
         * JSON config for child-scoped modules. See ModuleBlueprint.parentContextJson javadoc.
         * e.g. { "parentEntityType": "AUDIT_ENGAGEMENT", "parentIdParam": "engagementId",
         *        "apiBasePath": "/v1/audit/engagements/{engagementId}/controls" }
         */
        private String            parentContextJson;
        /**
         * STOMP WebSocket topic pattern for live detail-page updates.
         * e.g. "/topic/module/issue/{id}" — {id} replaced with entity ID at runtime.
         * See ModuleBlueprint.wsTopicPattern javadoc.
         */
        private String            wsTopicPattern;
        /**
         * Navigation key — matches ui_navigation.nav_key.
         * Used by the sidebar to highlight the active nav item.
         * e.g. "audit_tests", "org_audit_policies". Null = no highlight.
         */
        private String            navKey;
    }
}