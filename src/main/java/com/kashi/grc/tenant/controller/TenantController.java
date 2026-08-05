package com.kashi.grc.tenant.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.DbRepository;
import com.kashi.grc.common.service.MailService;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.dto.request.TenantCreateRequest;
import com.kashi.grc.tenant.dto.request.TenantUpdateRequest;
import com.kashi.grc.tenant.dto.response.TenantResponse;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/tenants")
@Tag(name = "Tenant Management", description = "Tenant provisioning and configuration")
@RequiredArgsConstructor
public class TenantController {

    private final TenantRepository tenantRepository;
    private final UserRepository   userRepository;

    @org.springframework.beans.factory.annotation.Value("${kashi.app.base-url:https://app.kashigrc.com}")
    private String appBaseUrl;
    private final DbRepository     dbRepository;
    private final UtilityService   utilityService;
    private final MailService      mailService;
    private final com.kashi.grc.uiconfig.repository.FeatureFlagRepository featureFlagRepository;
    private final com.kashi.grc.uiconfig.service.FeatureEntitlementService featureEntitlementService;

    // ── CREATE ────────────────────────────────────────────────────
    @PostMapping
    @Operation(summary = "Create a new tenant")
    public ResponseEntity<ApiResponse<TenantResponse>> createTenant(
            @Valid @RequestBody TenantCreateRequest req) {

        // Pre-checks give a clean, field-specific message instead of letting
        // the DB's unique constraint on `code` (and this new check on `name`)
        // surface as a raw DataIntegrityViolationException — see
        // GlobalExceptionHandler for the defense-in-depth catch on that
        // exception type for any case this pre-check doesn't cover (e.g. a
        // race between two concurrent creates with the same code).
        if (tenantRepository.existsByNameIgnoreCase(req.getName())) {
            throw new BusinessException("TENANT_NAME_TAKEN",
                    "An organization named \"" + req.getName() + "\" already exists", HttpStatus.CONFLICT);
        }
        if (req.getCode() != null && tenantRepository.existsByCode(req.getCode())) {
            throw new BusinessException("TENANT_CODE_TAKEN",
                    "Organization code \"" + req.getCode() + "\" is already in use", HttpStatus.CONFLICT);
        }

        Tenant t = Tenant.builder()
                .name(req.getName()).code(req.getCode())
                .description(req.getDescription()).plan(req.getPlan())
                .maxUsers(req.getMaxUsers()).maxVendors(req.getMaxVendors())
                .status("ACTIVE")
                .build();
        tenantRepository.save(t);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(t)));
    }

    // ── FEATURE ENTITLEMENTS ──────────────────────────────────────
    // The feature CATALOGUE = global rows (tenant_id IS NULL). A tenant is
    // ENTITLED to a feature iff an explicit enabled row exists for it
    // (tenant_id = X). This is Model B: absence = not licensed, one clean query
    // answers "what does this tenant have". Global catalogue rows define what's
    // licensable; they never themselves grant (they are seeded disabled).

    @GetMapping("/{tenantId}/features")
    @Operation(summary = "List the feature catalogue with this tenant's entitlement state")
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> getTenantFeatures(
            @PathVariable Long tenantId) {
        // catalogue: global definitions
        var catalogue = featureFlagRepository.findByTenantIdIsNull();
        // this tenant's ACTIVE (non-soft-deleted) rows, keyed for quick lookup
        var tenantRows = featureFlagRepository.findByTenantId(tenantId).stream()
                .filter(f -> f.getDeletedAt() == null)
                .collect(java.util.stream.Collectors.toMap(
                        f -> f.getFlagKey(), f -> f, (a, b) -> a));

        var out = new java.util.ArrayList<Map<String, Object>>();
        for (var def : catalogue) {
            if (def.getDeletedAt() != null) continue;   // skip retired catalogue rows
            var row = tenantRows.get(def.getFlagKey());
            String mode = def.getMode() != null ? def.getMode() : "GLOBAL";
            boolean enabled = "LICENSED".equals(mode)
                    ? (row != null && row.isEnabled())   // licensed: tenant row decides
                    : def.isEnabled();                    // global: same for all tenants
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("flagKey",     def.getFlagKey());
            m.put("description", def.getDescription());
            m.put("mode",        mode);                   // GLOBAL or LICENSED
            m.put("enabled",     enabled);
            m.put("togglable",   "LICENSED".equals(mode)); // per-tenant toggle only in LICENSED
            out.add(m);
        }
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @PutMapping("/{tenantId}/features/{flagKey}")
    @Operation(summary = "Enable or disable a feature for a specific tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setTenantFeature(
            @PathVariable Long tenantId, @PathVariable String flagKey,
            @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        Long actor = utilityService.getLoggedInDataContext().getId();

        // Routed through the service: valid only when the feature is LICENSED,
        // and it maintains the soft-delete / single-active-row-set invariant.
        featureEntitlementService.setTenant(flagKey, tenantId, enabled, actor);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "flagKey", flagKey, "tenantId", tenantId, "enabled", enabled)));
    }

    // ── GET BY ID ─────────────────────────────────────────────────
    @GetMapping("/{tenantId}")
    @Operation(summary = "Get tenant by ID")
    public ResponseEntity<ApiResponse<TenantResponse>> getTenant(@PathVariable Long tenantId) {
        Tenant t = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        return ResponseEntity.ok(ApiResponse.success(toResponse(t)));
    }

    // ── LIST ──────────────────────────────────────────────────────
    @GetMapping
    @Operation(summary = "List tenants — paginated, filterable, sortable")
    public ResponseEntity<ApiResponse<PaginatedResponse<TenantResponse>>> listTenants(
            @RequestParam Map<String, String> allParams) {
        PaginatedResponse<TenantResponse> page = dbRepository.findAll(
                Tenant.class,
                utilityService.getpageDetails(allParams),
                (cb, root) -> List.of(),
                (cb, root) -> Map.of(
                        "name",   root.get("name"),
                        "code",   root.get("code"),
                        "status", root.get("status"),
                        "plan",   root.get("plan")
                ),
                this::toResponse
        );
        return ResponseEntity.ok(ApiResponse.success(page));
    }

    // ── UPDATE ────────────────────────────────────────────────────
    @PutMapping("/{tenantId}")
    @Operation(summary = "Update tenant")
    public ResponseEntity<ApiResponse<TenantResponse>> updateTenant(
            @PathVariable Long tenantId,
            @Valid @RequestBody TenantUpdateRequest req) {
        Tenant t = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        if (req.getName()        != null) t.setName(req.getName());
        if (req.getDescription() != null) t.setDescription(req.getDescription());
        if (req.getPlan()        != null) t.setPlan(req.getPlan());
        if (req.getStatus()      != null) t.setStatus(req.getStatus());
        if (req.getMaxUsers()    != null) t.setMaxUsers(req.getMaxUsers());
        if (req.getMaxVendors()  != null) t.setMaxVendors(req.getMaxVendors());
        tenantRepository.save(t);
        return ResponseEntity.ok(ApiResponse.success(toResponse(t)));
    }

    // ── GET OWNER ─────────────────────────────────────────────────
    @GetMapping("/{tenantId}/owner")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @Operation(summary = "Get the primary owner (first ORGANIZATION user) for a tenant")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTenantOwner(
            @PathVariable Long tenantId) {

        tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));

        List<com.kashi.grc.usermanagement.domain.User> users =
                userRepository.findByTenantIdAndIsDeletedFalse(tenantId);

        if (users.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(Map.of()));
        }

        // Owner = earliest ORGANIZATION-side user; fallback to earliest any user
        var owner = users.stream()
                .filter(u -> u.getRoles().stream()
                        .anyMatch(r -> r.getSide() != null &&
                                r.getSide().name().equals("ORGANIZATION")))
                .min(Comparator.comparing(
                        u -> u.getCreatedAt() != null ? u.getCreatedAt() : LocalDateTime.MIN))
                .orElseGet(() -> users.stream()
                        .min(Comparator.comparing(
                                u -> u.getCreatedAt() != null ? u.getCreatedAt() : LocalDateTime.MIN))
                        .orElse(users.get(0)));

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "userId",    owner.getId(),
                "email",     owner.getEmail(),
                "firstName", owner.getFirstName() != null ? owner.getFirstName() : "",
                "lastName",  owner.getLastName()  != null ? owner.getLastName()  : "",
                "fullName",  owner.getFullName()  != null ? owner.getFullName()  : "",
                "role",      "OWNER"
        )));
    }

    // ── SEND WELCOME EMAIL ────────────────────────────────────────
    @PostMapping("/{tenantId}/send-welcome-email")
    @Operation(summary = "Send welcome email to tenant owner — rendered server-side from DB template")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendWelcomeEmail(
            @PathVariable Long tenantId,
            @RequestBody WelcomeEmailRequest req) {

        Tenant t = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));

        if (req == null || !StringUtils.hasText(req.getEmail())) {
            throw new BusinessException("NO_RECIPIENT", "Recipient email is required");
        }

        String firstName = StringUtils.hasText(req.getAdminFirstName())
                ? req.getAdminFirstName() : "Admin";
        String loginUrl  = StringUtils.hasText(req.getLoginUrl())
                ? req.getLoginUrl() : appBaseUrl + "/auth/login";
        String tempPwd   = StringUtils.hasText(req.getTempPassword())
                ? req.getTempPassword() : "(see your original welcome email)";

        mailService.send("user-invitation", req.getEmail(), Map.ofEntries(
                Map.entry("firstName",         firstName),
                Map.entry("email",             req.getEmail()),
                Map.entry("admin_email",       req.getEmail()),
                Map.entry("tempPassword",      tempPwd),
                Map.entry("temp_password",     tempPwd),
                Map.entry("loginUrl",          loginUrl),
                Map.entry("login_url",         loginUrl),
                Map.entry("resetUrl",          loginUrl),
                Map.entry("organization_name", t.getName()),
                Map.entry("supportUrl",        "https://support.kashigrc.com"),
                Map.entry("support_url",       "https://support.kashigrc.com"),
                Map.entry("admin_name",        firstName)
        ));

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "sent",       true,
                "to",         req.getEmail(),
                "tenantId",   tenantId,
                "tenantName", t.getName()
        )));
    }

    // ── MAPPER ────────────────────────────────────────────────────
    private TenantResponse toResponse(Tenant t) {
        return TenantResponse.builder()
                .tenantId(t.getId()).name(t.getName()).code(t.getCode())
                .description(t.getDescription()).status(t.getStatus())
                .plan(t.getPlan()).maxUsers(t.getMaxUsers()).maxVendors(t.getMaxVendors())
                .createdAt(t.getCreatedAt())
                .build();
    }

    // ── DTOs ──────────────────────────────────────────────────────
    @Data
    public static class WelcomeEmailRequest {
        private String email;
        private String subject;       // ignored — always rendered from DB template
        private String body;          // ignored — always rendered from DB template
        private String adminFirstName;
        private String loginUrl;
        private String tempPassword;
    }
}