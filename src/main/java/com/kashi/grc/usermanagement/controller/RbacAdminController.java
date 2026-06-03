package com.kashi.grc.usermanagement.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.guard.domain.SodRule;
import com.kashi.grc.guard.repository.SodRuleRepository;
import com.kashi.grc.usermanagement.domain.*;
import com.kashi.grc.usermanagement.repository.*;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * RBAC Admin — fine-grained permissions, role grants, user overrides, SoD rules.
 *
 * All endpoints Platform Admin only (SYSTEM side — enforced at SecurityConfig).
 *
 * PERMISSIONS:
 *   GET/POST         /v1/admin/rbac/permissions
 *   PUT/DELETE       /v1/admin/rbac/permissions/{id}
 *
 * ROLE GRANTS:
 *   GET              /v1/admin/rbac/roles/{roleId}/grants
 *   POST             /v1/admin/rbac/roles/{roleId}/grants   (upsert)
 *   DELETE           /v1/admin/rbac/grants/{id}
 *
 * USER OVERRIDES:
 *   GET/POST         /v1/admin/rbac/user-overrides
 *   PATCH            /v1/admin/rbac/user-overrides/{id}/revoke
 *
 * SOD RULES:
 *   GET/POST         /v1/admin/rbac/sod-rules
 *   PUT/DELETE       /v1/admin/rbac/sod-rules/{id}
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/rbac")
@Tag(name = "RBAC Admin", description = "Fine-grained permissions, role grants, user overrides, SoD rules")
@RequiredArgsConstructor
public class RbacAdminController {

    private final PermissionRepository            permissionRepository;
    private final PermissionGrantRepository       permissionGrantRepository;
    private final UserPermissionOverrideRepository overrideRepository;
    private final SodRuleRepository               sodRuleRepository;
    private final RoleRepository                  roleRepository;
    private final UserRepository                  userRepository;
    private final DbRepository                    dbRepository;
    private final UtilityService                  utilityService;


    // ══════════════════════════════════════════════════════════════
    // SUMMARY — badge counts for RBAC admin page tabs
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/summary")
    @Operation(summary = "Badge counts for all RBAC admin tabs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSummary() {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        // Use count queries — avoids loading entity graphs (no N+1 on Role.permissions)
        long permCount     = permissionRepository.count();
        long roleCount     = roleRepository.countByTenantId(tenantId)
                + roleRepository.countByTenantIdIsNull();
        long overrideCount = overrideRepository.count();
        long sodCount      = sodRuleRepository.countActiveForTenant(tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("permissions", permCount);
        m.put("roles",       roleCount);
        m.put("overrides",   overrideCount);
        m.put("sodRules",    sodCount);
        return ResponseEntity.ok(ApiResponse.success(m));
    }

    // ══════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/permissions")
    @Operation(summary = "List permissions — paginated, filter by search and module")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listPermissions(
            @RequestParam Map<String, String> allParams) {
        String search = allParams.getOrDefault("search", "");
        String module = allParams.getOrDefault("module", "");
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                Permission.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
                    if (!search.isBlank()) p.add(cb.or(
                            cb.like(cb.lower(root.get("code")), "%" + search.toLowerCase() + "%"),
                            cb.like(cb.lower(root.get("name")), "%" + search.toLowerCase() + "%")));
                    if (!module.isBlank()) p.add(cb.equal(root.get("module"), module));
                    return p;
                },
                (cb, root) -> Map.of("code", root.get("code")),
                perm -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",           perm.getId());
                    m.put("code",         perm.getCode());
                    m.put("name",         perm.getName() != null ? perm.getName() : "");
                    String permModule = perm.getModule() != null && !perm.getModule().isBlank()
                            ? perm.getModule() : deriveModuleFromResourceType(perm.getResourceType());
                    m.put("module",       permModule);
                    m.put("resourceType", perm.getResourceType() != null ? perm.getResourceType() : "");
                    return m;
                })));
    }

    @PostMapping("/permissions")
    @Operation(summary = "Create a permission code (dot notation: risk.approve)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPermission(
            @Valid @RequestBody PermissionRequest req) {
        Permission p = Permission.builder()
                .code(req.getCode().toLowerCase())
                .name(req.getName())
                .module(req.getModule())
                .resourceType(req.getResourceType())
                .build();
        permissionRepository.save(p);
        log.info("[RBAC] Permission created: {}", p.getCode());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", p.getId(), "code", p.getCode())));
    }

    @PutMapping("/permissions/{id}")
    @Operation(summary = "Update permission name or module")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updatePermission(
            @PathVariable Long id, @RequestBody PermissionRequest req) {
        Permission p = permissionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Permission", id));
        if (req.getName()         != null) p.setName(req.getName());
        if (req.getModule()       != null) p.setModule(req.getModule());
        if (req.getResourceType() != null) p.setResourceType(req.getResourceType());
        permissionRepository.save(p);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", p.getId(), "code", p.getCode())));
    }

    @DeleteMapping("/permissions/{id}")
    @Operation(summary = "Delete permission — also removes all grants and overrides referencing it")
    public ResponseEntity<ApiResponse<Void>> deletePermission(@PathVariable Long id) {
        permissionGrantRepository.deleteByPermissionId(id);
        overrideRepository.deleteByPermissionId(id);
        permissionRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // ROLE GRANTS
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/roles/{roleId}/grants")
    @Operation(summary = "List all permission grants for a role with permission codes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listGrants(
            @PathVariable Long roleId) {
        List<Map<String, Object>> result = permissionGrantRepository
                .findByRoleIdWithPermission(roleId)
                .stream()
                .map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",             g.getId());
                    m.put("roleId",         g.getRoleId());
                    m.put("permissionId",   g.getPermissionId());
                    m.put("permissionCode", g.getPermissionCode() != null ? g.getPermissionCode() : "");
                    m.put("granted",        g.isGranted());
                    m.put("notes",          g.getNotes() != null ? g.getNotes() : "");
                    return m;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * GET /v1/admin/rbac/permissions/{permCode}/roles
     *
     * Returns all roles that currently hold the given permission code.
     * Used by NavigationAdminPage to show "who can see this nav item?"
     * and to power the permission→role grant/revoke modal.
     *
     * Response: [ { grantId, roleId, roleName, roleSide, roleLevel, granted } ]
     */
    @GetMapping("/permissions/{permCode}/roles")
    @Operation(summary = "List all roles that hold a given permission code")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRolesForPermission(
            @PathVariable String permCode) {

        // Find the permission by code
        Permission perm = permissionRepository.findByCode(permCode)
                .orElseThrow(() -> new ResourceNotFoundException("Permission", "code", permCode));

        // Find all grants for this permission
        List<Map<String, Object>> result = permissionGrantRepository
                .findByPermissionId(perm.getId())
                .stream()
                .filter(PermissionGrant::isGranted)   // only active grants
                .map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("grantId",   g.getId());
                    m.put("roleId",    g.getRoleId());
                    m.put("granted",   g.isGranted());
                    // Enrich with role details
                    roleRepository.findById(g.getRoleId()).ifPresent(r -> {
                        m.put("roleName",  r.getName());
                        m.put("roleSide",  r.getSide());
                        m.put("roleLevel", r.getLevel());
                    });
                    return m;
                })
                .filter(m -> m.containsKey("roleName"))  // skip orphaned grants
                .toList();

        return ResponseEntity.ok(ApiResponse.success(result));
    }


    @PostMapping("/roles/{roleId}/grants")
    @Operation(summary = "Upsert permission grant for a role — creates or updates existing")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upsertGrant(
            @PathVariable Long roleId, @Valid @RequestBody GrantRequest req) {
        Long actorId = utilityService.getLoggedInDataContext().getId();
        PermissionGrant grant = permissionGrantRepository
                .findByRoleIdAndPermissionId(roleId, req.getPermissionId())
                .orElse(PermissionGrant.builder()
                        .roleId(roleId)
                        .permissionId(req.getPermissionId())
                        .grantedBy(actorId)
                        .build());
        grant.setGranted(req.isGranted());
        grant.setNotes(req.getNotes());
        grant.setGrantedBy(actorId);
        permissionGrantRepository.save(grant);
        log.info("[RBAC] Grant upserted role={} perm={} granted={}", roleId, req.getPermissionId(), req.isGranted());
        return ResponseEntity.ok(ApiResponse.success(
                toMap("id", grant.getId(), "roleId", roleId, "granted", grant.isGranted())));
    }

    @DeleteMapping("/grants/{id}")
    @Operation(summary = "Remove a permission grant from a role")
    public ResponseEntity<ApiResponse<Void>> deleteGrant(@PathVariable Long id) {
        permissionGrantRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════════════════════════════════════════════
    // USER PERMISSION OVERRIDES
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/user-overrides")
    @Operation(summary = "List user permission overrides — paginated, filterable")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listOverrides(
            @RequestParam Map<String, String> allParams) {
        String userIdFilter  = allParams.getOrDefault("userId", "");
        String activeFilter  = allParams.getOrDefault("isActive", "");
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                UserPermissionOverride.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> {
                    List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
                    if (!userIdFilter.isBlank())
                        p.add(cb.equal(root.get("userId"), Long.parseLong(userIdFilter)));
                    if ("true".equalsIgnoreCase(activeFilter))
                        p.add(cb.isTrue(root.get("isActive")));
                    else if ("false".equalsIgnoreCase(activeFilter))
                        p.add(cb.isFalse(root.get("isActive")));
                    return p;
                },
                (cb, root) -> Map.of("createdat", root.get("createdAt")),
                ov -> {
                    String userName = userRepository.findById(ov.getUserId())
                            .map(u -> u.getFirstName() + " " + u.getLastName()).orElse("—");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",             ov.getId());
                    m.put("userId",         ov.getUserId());
                    m.put("userName",       userName);
                    m.put("userEmail",      userRepository.findById(ov.getUserId())
                            .map(User::getEmail).orElse(""));
                    m.put("permissionId",   ov.getPermissionId());
                    m.put("permissionCode", ov.getPermissionCode() != null ? ov.getPermissionCode() : "");
                    m.put("granted",        ov.isGranted());
                    m.put("reason",         ov.getReason() != null ? ov.getReason() : "");
                    m.put("expiresAt",      ov.getExpiresAt());
                    m.put("isActive",       ov.isActive());
                    m.put("grantedBy",      ov.getGrantedBy());
                    m.put("revokedAt",      ov.getRevokedAt());
                    m.put("createdAt",      ov.getCreatedAt());
                    return m;
                })));
    }

    @PostMapping("/user-overrides")
    @Operation(summary = "Create user permission override — grant or deny beyond their role")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createOverride(
            @Valid @RequestBody UserOverrideRequest req) {
        Long actorId = utilityService.getLoggedInDataContext().getId();

        // Resolve permission code (denormalized for hot-path reads in WorkflowAccessService)
        String permCode = null;
        if (req.getPermissionId() != null) {
            permCode = permissionRepository.findById(req.getPermissionId())
                    .map(Permission::getCode).orElse(null);
        } else if (req.getPermissionCode() != null) {
            permCode = req.getPermissionCode();
            req.setPermissionId(permissionRepository.findByCode(req.getPermissionCode())
                    .map(Permission::getId).orElse(null));
        }

        UserPermissionOverride ov = UserPermissionOverride.builder()
                .userId(req.getUserId())
                .permissionId(req.getPermissionId())
                .permissionCode(permCode)
                .granted(req.isGranted())
                .reason(req.getReason())
                .expiresAt(req.getExpiresAt() != null ? LocalDateTime.parse(req.getExpiresAt()) : null)
                .grantedBy(actorId)
                .isActive(true)
                .build();
        overrideRepository.save(ov);
        log.info("[RBAC] Override created user={} perm={} granted={}", req.getUserId(), permCode, req.isGranted());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap(
                        "id", ov.getId(), "userId", ov.getUserId(),
                        "permissionCode", permCode, "granted", ov.isGranted())));
    }

    @PatchMapping("/user-overrides/{id}/revoke")
    @Operation(summary = "Revoke a user permission override")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeOverride(@PathVariable Long id) {
        Long actorId = utilityService.getLoggedInDataContext().getId();
        UserPermissionOverride ov = overrideRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("UserPermissionOverride", id));
        ov.setActive(false);
        ov.setRevokedAt(LocalDateTime.now());
        ov.setRevokedBy(actorId);
        overrideRepository.save(ov);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", id, "isActive", false)));
    }

    // ══════════════════════════════════════════════════════════════
    // SOD RULES
    // ══════════════════════════════════════════════════════════════

    @GetMapping("/sod-rules")
    @Operation(summary = "List SoD rules — paginated, global + tenant-scoped")
    public ResponseEntity<ApiResponse<PaginatedResponse<Map<String, Object>>>> listSodRules(
            @RequestParam Map<String, String> allParams) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(dbRepository.findAll(
                SodRule.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(cb.or(
                        cb.isNull(root.get("tenantId")),        // NULL = global rule
                        cb.equal(root.get("tenantId"), tenantId))),
                (cb, root) -> Map.of("rulename", root.get("ruleName")),
                r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",           r.getId());
                    m.put("ruleType",     r.getRuleType() != null ? r.getRuleType().name() : "ROLE_PAIR");
                    m.put("ruleName",     r.getRuleName());
                    m.put("description",  r.getDescription() != null ? r.getDescription() : "");
                    m.put("permissionA",  r.getPermissionA());
                    m.put("permissionB",  r.getPermissionB());
                    // For ROLE_PAIR rules, expose role names so the frontend can render them
                    m.put("role1Name",    r.getConflictingRole1() != null ? r.getConflictingRole1().getName() : null);
                    m.put("role2Name",    r.getConflictingRole2() != null ? r.getConflictingRole2().getName() : null);
                    m.put("conflictType", r.getConflictType() != null ? r.getConflictType().name() : "HARD");
                    m.put("scope",        r.getScope() != null ? r.getScope().name() : "GLOBAL");
                    m.put("entityTypes",  r.getEntityTypes() != null ? r.getEntityTypes() : "");
                    m.put("frameworkRef", r.getFrameworkRef() != null ? r.getFrameworkRef() : "");
                    m.put("isActive",     r.isActive());
                    return m;
                })));
    }

    @PostMapping("/sod-rules")
    @Operation(summary = "Create a SoD rule (ROLE_PAIR or PERMISSION_PAIR)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSodRule(
            @Valid @RequestBody SodRuleRequest req) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        // Auto-detect type: role IDs present -> ROLE_PAIR, else PERMISSION_PAIR
        boolean isRolePair = "ROLE_PAIR".equalsIgnoreCase(req.getRuleType())
                || (req.getRole1Id() != null && req.getRole2Id() != null);
        SodRule.RuleType ruleType = isRolePair
                ? SodRule.RuleType.ROLE_PAIR : SodRule.RuleType.PERMISSION_PAIR;
        String scope = req.getScope() != null ? req.getScope()
                : (isRolePair ? "GLOBAL" : "INSTANCE");
        SodRule.SodRuleBuilder builder = SodRule.builder()
                .ruleType(ruleType)
                .ruleName(req.getRuleName())
                .description(req.getDescription())
                .conflictType(req.getConflictType() != null
                        ? SodRule.ConflictType.valueOf(req.getConflictType())
                        : SodRule.ConflictType.HARD)
                .scope(SodRule.SodScope.valueOf(scope))
                .entityTypes(req.getEntityTypes())
                .frameworkRef(req.getFrameworkRef())
                .isActive(true)
                .tenantId(tenantId);
        if (isRolePair) {
            if (req.getRole1Id() != null)
                roleRepository.findById(req.getRole1Id()).ifPresent(builder::conflictingRole1);
            if (req.getRole2Id() != null)
                roleRepository.findById(req.getRole2Id()).ifPresent(builder::conflictingRole2);
        } else {
            builder.permissionA(req.getPermissionA()).permissionB(req.getPermissionB());
        }
        SodRule rule = sodRuleRepository.save(builder.build());
        log.info("[SOD] Rule created: '{}' type={}", rule.getRuleName(), ruleType);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toMap("id", rule.getId(), "ruleName", rule.getRuleName())));
    }

    @PutMapping("/sod-rules/{id}")
    @Operation(summary = "Update a SoD rule")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSodRule(
            @PathVariable Long id, @RequestBody SodRuleRequest req) {
        SodRule r = sodRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SodRule", id));
        if (req.getRuleName()    != null) r.setRuleName(req.getRuleName());
        if (req.getDescription() != null) r.setDescription(req.getDescription());
        if (req.getPermissionA() != null) r.setPermissionA(req.getPermissionA());
        if (req.getPermissionB() != null) r.setPermissionB(req.getPermissionB());
        if (req.getConflictType()!= null) r.setConflictType(SodRule.ConflictType.valueOf(req.getConflictType()));
        if (req.getScope()       != null) r.setScope(SodRule.SodScope.valueOf(req.getScope()));
        if (req.getEntityTypes() != null) r.setEntityTypes(req.getEntityTypes());
        if (req.getFrameworkRef()!= null) r.setFrameworkRef(req.getFrameworkRef());
        if (req.getActive()      != null) r.setActive(req.getActive());
        sodRuleRepository.save(r);
        return ResponseEntity.ok(ApiResponse.success(toMap("id", r.getId(), "ruleName", r.getRuleName())));
    }

    @DeleteMapping("/sod-rules/{id}")
    @Operation(summary = "Delete a SoD rule")
    public ResponseEntity<ApiResponse<Void>> deleteSodRule(@PathVariable Long id) {
        sodRuleRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ── Utils ─────────────────────────────────────────────────────────────────
    /** Derives a module name from resource_type for legacy permissions without an explicit module. */
    private String deriveModuleFromResourceType(String resourceType) {
        if (resourceType == null || resourceType.isBlank()) return "SYSTEM";
        return switch (resourceType.toUpperCase()) {
            case "AUDIT_ENGAGEMENT", "AUDIT_SECTION", "AUDIT_CONTROL",
                 "AUDIT_FINDING", "AUDIT_POLICY", "AUDIT_REPORT", "AUDIT" -> "AUDIT";
            case "VENDOR"                    -> "VENDOR";
            case "ASSESSMENT"                -> "ASSESSMENT";
            case "USER", "ROLE"              -> "USER_MGMT";
            case "WORKFLOW", "WORKFLOW_TASK" -> "WORKFLOW";
            case "ISSUE"                     -> "ISSUE";
            case "REPORT"                    -> "REPORT";
            case "DOCUMENT"                  -> "DOCUMENT";
            default                          -> "SYSTEM";
        };
    }

    private Map<String, Object> toMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length - 1; i += 2) m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }

    // ── Inner request DTOs ────────────────────────────────────────────────────

    @Data public static class PermissionRequest {
        @NotBlank private String code;
        @NotBlank private String name;
        private String module;
        private String resourceType;
    }

    @Data public static class GrantRequest {
        @NotNull private Long   permissionId;
        private boolean         granted = true;
        private String          notes;
    }

    @Data public static class UserOverrideRequest {
        @NotNull private Long   userId;
        private Long            permissionId;
        private String          permissionCode;
        private boolean         granted = true;
        @NotBlank private String reason;
        private String          expiresAt;   // ISO datetime, null = permanent
    }

    @Data public static class SodRuleRequest {
        @NotBlank private String  ruleName;
        private String            description;
        // PERMISSION_PAIR: provide permissionA + permissionB
        private String            permissionA;
        private String            permissionB;
        // ROLE_PAIR: provide role1Id + role2Id
        private Long              role1Id;
        private Long              role2Id;
        // "ROLE_PAIR" or "PERMISSION_PAIR" (auto-detected if omitted)
        private String            ruleType;
        private String            conflictType = "HARD";
        private String            scope        = "GLOBAL";
        private String            entityTypes;
        private String            frameworkRef;
        private Boolean           active;
    }
}