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

@lombok.extern.slf4j.Slf4j
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
    private final com.kashi.grc.firmverification.service.AuditFirmVerificationService firmVerificationService;
    private final com.kashi.grc.usermanagement.repository.AccountStatusChangeRepository statusChangeRepository;
    private final com.kashi.grc.usermanagement.service.user.OrgOwnerService orgOwnerService;
    private final com.kashi.grc.common.security.AccountAccessService accountAccess;
    private final com.kashi.grc.common.security.LiveSessionRegistry liveSessions;

    /** The statuses a tenant can be put in by hand. CANCELLED is set by billing. */
    private static final java.util.Set<String> TENANT_STATUSES = java.util.Set.of("ACTIVE", "TRIAL", "SUSPENDED");

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

        // is_audit_firm is NEVER set here. It is written only by
        // AuditFirmVerificationService when a platform reviewer approves an
        // application with evidence and the firm's email confirmation. Ticking
        // "audit firm" on Create Tenant now opens that application as a DRAFT,
        // to be completed on the tenant's Audit firm tab.
        Tenant t = Tenant.builder()
                .name(req.getName()).code(req.getCode())
                .description(req.getDescription()).plan(req.getPlan())
                .maxUsers(req.getMaxUsers()).maxVendors(req.getMaxVendors())
                .isAuditFirm(false)
                .acquisitionChannel(req.getAcquisitionChannel())
                .industry(req.getIndustry())
                .employeeBand(req.getEmployeeBand())
                .country(req.getCountry() != null ? req.getCountry().toUpperCase() : null)
                .status("ACTIVE")
                .build();
        tenantRepository.save(t);
        if (Boolean.TRUE.equals(req.getIsAuditFirm())) {
            firmVerificationService.openDraft(t.getId(), utilityService.getLoggedInDataContext().getId());
        }
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
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTenantFeatures(
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
        if (req.getStatus()      != null) changeStatus(t, req.getStatus(), req.getStatusReason());
        if (req.getMaxUsers()    != null) t.setMaxUsers(req.getMaxUsers());
        if (req.getMaxVendors()  != null) t.setMaxVendors(req.getMaxVendors());
        // Commercial profile and bill-to. An empty string clears a field.
        if (req.getAcquisitionChannel() != null) t.setAcquisitionChannel(blankToNull(req.getAcquisitionChannel()));
        if (req.getIndustry()           != null) t.setIndustry(blankToNull(req.getIndustry()));
        if (req.getEmployeeBand()       != null) t.setEmployeeBand(blankToNull(req.getEmployeeBand()));
        if (req.getCountry()            != null) {
            String c = blankToNull(req.getCountry());
            t.setCountry(c == null ? null : c.toUpperCase());
        }
        if (req.getLegalName()      != null) t.setLegalName(blankToNull(req.getLegalName()));
        if (req.getBillingEmail()   != null) t.setBillingEmail(blankToNull(req.getBillingEmail()));
        if (req.getBillingAddress() != null) t.setBillingAddress(blankToNull(req.getBillingAddress()));
        if (req.getTaxId()          != null) t.setTaxId(blankToNull(req.getTaxId()));
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

        // The recorded owner (tenants.owner_user_id) when there is one. Older
        // tenants without it fall back to the earliest organisation-side user,
        // as before, until the backfill has run.
        Long ownerId = tenantRepository.findById(tenantId).map(Tenant::getOwnerUserId).orElse(null);
        var recorded = ownerId == null ? null
                : users.stream().filter(u -> u.getId().equals(ownerId)).findFirst().orElse(null);
        var owner = recorded != null ? recorded : users.stream()
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

    // ── STATUS ────────────────────────────────────────────────────

    /**
     * Suspend or reactivate an organisation. Used to be a free-text field set
     * through this update with no validation and no record of who did it.
     *
     * SUSPENDED needs a reason and starts the read-only grace period
     * (AccountAccessService): the organisation's people can still sign in, view
     * and export, but not change anything; after the grace period access is
     * blocked. ACTIVE / TRIAL clear it. Takes effect on everyone's next request.
     */
    private void changeStatus(Tenant t, String raw, String reason) {
        String to = raw == null ? "" : raw.trim().toUpperCase();
        if (!TENANT_STATUSES.contains(to)) {
            throw new BusinessException("INVALID_STATUS", "Status must be ACTIVE, TRIAL or SUSPENDED.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String from = t.getStatus() == null ? "ACTIVE" : t.getStatus().toUpperCase();
        if (to.equals(from)) return;
        if (com.kashi.grc.common.util.Constants.SYSTEM_TENANT_ID.equals(t.getId()) && "SUSPENDED".equals(to)) {
            throw new BusinessException("SYSTEM_TENANT", "The platform's own organisation can't be suspended.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if ("SUSPENDED".equals(to) && (reason == null || reason.isBlank())) {
            throw new BusinessException("REASON_REQUIRED", "A reason is required to suspend an organisation.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        t.setStatus(to);
        t.setSuspendedAt("SUSPENDED".equals(to) ? LocalDateTime.now() : null);
        statusChangeRepository.save(com.kashi.grc.usermanagement.domain.AccountStatusChange.builder()
                .subjectType(com.kashi.grc.usermanagement.domain.AccountStatusChange.TENANT)
                .subjectId(t.getId())
                .tenantId(t.getId())
                .fromStatus(from)
                .toStatus(to)
                .reason(reason == null || reason.isBlank() ? null : reason.trim())
                .actorUserId(utilityService.getLoggedInDataContext().getId())
                .detail("SUSPENDED".equals(to)
                        ? "read-only for " + accountAccess.readOnlyDays() + " days, then blocked" : null)
                .build());
        com.kashi.grc.common.security.AccountAccessService.invalidateAll();
        // Open connections of people this change blocks are closed once it is saved.
        liveSessions.sweepAfterCommit();
        log.info("[TENANT] Status {} → {} | tenantId={} | reason={}", from, to, t.getId(), reason);
    }

    @GetMapping("/{tenantId}/status-history")
    @Operation(summary = "Who changed this organisation's status or owner, when and why")
    public ResponseEntity<ApiResponse<List<com.kashi.grc.usermanagement.domain.AccountStatusChange>>> statusHistory(
            @PathVariable Long tenantId) {
        return ResponseEntity.ok(ApiResponse.success(statusChangeRepository
                .findBySubjectTypeAndSubjectIdOrderByIdDesc(
                        com.kashi.grc.usermanagement.domain.AccountStatusChange.TENANT, tenantId)));
    }

    @PostMapping("/{tenantId}/owner/transfer")
    @Operation(summary = "Make another of the organisation's people its owner (platform)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> transferOwner(
            @PathVariable Long tenantId, @RequestBody Map<String, Object> body) {
        Object uid = body.get("userId");
        if (uid == null) throw new BusinessException("USER_REQUIRED", "userId is required");
        return ResponseEntity.ok(ApiResponse.success(orgOwnerService.transfer(
                tenantId, Long.valueOf(uid.toString()),
                body.get("reason") == null ? null : body.get("reason").toString())));
    }

    // ── MAPPER ────────────────────────────────────────────────────
    private TenantResponse toResponse(Tenant t) {
        return TenantResponse.builder()
                .ownerUserId(t.getOwnerUserId())
                .suspendedAt(t.getSuspendedAt())
                .readOnlyUntil("SUSPENDED".equalsIgnoreCase(t.getStatus()) ? accountAccess.graceEnd(t) : null)
                .tenantId(t.getId()).name(t.getName()).code(t.getCode())
                .description(t.getDescription()).status(t.getStatus())
                .plan(t.getPlan()).maxUsers(t.getMaxUsers()).maxVendors(t.getMaxVendors())
                .isAuditFirm(t.isAuditFirm())
                .createdAt(t.getCreatedAt())
                .acquisitionChannel(t.getAcquisitionChannel())
                .firstPaidAt(t.getFirstPaidAt())
                .industry(t.getIndustry())
                .employeeBand(t.getEmployeeBand())
                .country(t.getCountry())
                .legalName(t.getLegalName())
                .billingEmail(t.getBillingEmail())
                .billingAddress(t.getBillingAddress())
                .taxId(t.getTaxId())
                .build();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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