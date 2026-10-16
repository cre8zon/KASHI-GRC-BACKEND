package com.kashi.grc.tenant.service;

import com.kashi.grc.billing.domain.BillingPlan;
import com.kashi.grc.billing.domain.TenantSubscription;
import com.kashi.grc.billing.repository.BillingPlanRepository;
import com.kashi.grc.billing.service.SubscriptionService;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.firmverification.service.AuditFirmVerificationService;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.dto.request.TenantProvisionRequest;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.usermanagement.dto.request.UserCreateRequest;
import com.kashi.grc.usermanagement.dto.response.UserResponse;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.usermanagement.service.user.UserService;
import com.kashi.grc.billing.service.PublicPricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Creates a customer organisation in ONE transaction: the tenant, its first
 * administrator, its first subscription (trial or paid, from the plan
 * catalogue) with the plan's features applied, and — when asked — an
 * audit-firm verification draft.
 *
 * ── WHY ONE CALL ──────────────────────────────────────────────────────────
 * The old wizard made two calls from the browser: create the tenant, then
 * create the admin. When the second failed (an email already in use, which is
 * common) the tenant was left behind with no one able to log in, and the code
 * was burnt. Here any failure rolls back everything, and the caller gets a
 * field-level message to fix.
 *
 * ── WHAT IT REUSES, UNCHANGED ─────────────────────────────────────────────
 *   UserService.createUser            the same path /v1/users uses, including
 *                                     its cross-tenant and role-side guards
 *   SubscriptionService.create        writes the subscription, syncs plan /
 *                                     limits / status onto the tenant, and
 *                                     applies the plan's LICENSED features
 *   AuditFirmVerificationService      opens the firm application draft
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    /** Uppercase letter first, then letters, digits or underscore; 3–20 characters. */
    public static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,19}$");

    /**
     * Global role the first administrator receives — the same role the Create
     * Tenant screen has always assigned. Resolved by UserServiceImpl against
     * global roles (tenant_id IS NULL) with its side guards.
     */
    private static final String OWNER_ROLE = "ORG_OWNER";

    private final TenantRepository             tenantRepository;
    private final UserRepository               userRepository;
    private final UserService                  userService;
    private final BillingPlanRepository        planRepository;
    private final SubscriptionService          subscriptionService;
    private final AuditFirmVerificationService firmVerificationService;
    private final ApplicationEventPublisher    events;

    // ══════════════════════ AVAILABILITY ═════════════════════════════════════

    /**
     * Field-level checks the wizard runs before leaving a step. Only the
     * parameters supplied are checked. Keys in fieldErrors are the form's
     * field keys, so the wizard can pin each message to its input.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> availability(String name, String code, String adminEmail) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();

        if (name != null && !name.isBlank()) {
            if (tenantRepository.existsByNameIgnoreCase(name.trim())) {
                errors.put("name", "An organisation named \"" + name.trim() + "\" already exists.");
            }
        }
        if (code != null && !code.isBlank()) {
            String c = normaliseCode(code);
            out.put("normalizedCode", c);
            if (!CODE.matcher(c).matches()) {
                errors.put("code", "Use 3–20 characters: start with a letter, then letters, digits or _.");
            } else if (tenantRepository.existsByCode(c)) {
                errors.put("code", "Code " + c + " is already in use. Try " + suggestCode(c) + ".");
                out.put("suggestedCode", suggestCode(c));
            }
        } else if (name != null && !name.isBlank()) {
            out.put("suggestedCode", suggestCode(codeFromName(name)));
        }
        if (adminEmail != null && !adminEmail.isBlank()) {
            if (userRepository.existsByEmailAndIsDeletedFalse(adminEmail.trim().toLowerCase())
                    || userRepository.existsByEmailAndIsDeletedFalse(adminEmail.trim())) {
                errors.put("admin.email", "A user with this email already exists. Each person has one KashiGRC login.");
            }
        }
        out.put("fieldErrors", errors);
        out.put("ok", errors.isEmpty());
        return out;
    }

    // ══════════════════════ PROVISION ════════════════════════════════════════

    @Transactional
    public Map<String, Object> provision(TenantProvisionRequest req, Long actorId) {
        // Tenant code is optional: when the operator leaves it blank it is derived
        // from the organisation name (ACME_FINANCIAL, then ACME_FINANCIAL_2 …).
        if (blank(req.getCode()) && !blank(req.getName())) {
            req.setCode(suggestCode(codeFromName(req.getName().trim())));
        }
        Map<String, String> errors = validate(req);
        if (!errors.isEmpty()) throw invalid(errors);

        BillingPlan plan = planRepository.findByCode(req.getPlan().trim()).orElse(null);
        String code = normaliseCode(req.getCode());
        // A FREE plan (the audit-firm partner tier) has no trial and no contract:
        // it starts ACTIVE at zero value, which SubscriptionService only accepts
        // when told explicitly (allowZeroValue).
        boolean free = PublicPricingService.isFree(plan);
        boolean trial = !free && !"ACTIVE".equalsIgnoreCase(req.getStartAs());

        // 1. Tenant. Plan, limits and status are filled in by the subscription sync below.
        Tenant t = Tenant.builder()
                .name(req.getName().trim())
                .code(code)
                .legalName(blank(req.getLegalName()) ? null : req.getLegalName().trim())
                .description(blank(req.getDescription()) ? null : req.getDescription().trim())
                .country(blank(req.getCountry()) ? null : req.getCountry().trim().toUpperCase())
                .industry(blank(req.getIndustry()) ? null : req.getIndustry())
                .employeeBand(blank(req.getEmployeeBand()) ? null : req.getEmployeeBand())
                .acquisitionChannel(blank(req.getAcquisitionChannel()) ? null : req.getAcquisitionChannel())
                .plan(plan.getCode())
                .status(trial ? "TRIAL" : "ACTIVE")
                .isAuditFirm(false)
                .build();
        tenantRepository.save(t);

        // 2. First administrator — through the same service /v1/users uses.
        UserCreateRequest u = new UserCreateRequest();
        u.setTenantId(t.getId());
        u.setFirstName(req.getAdmin().getFirstName().trim());
        u.setLastName(req.getAdmin().getLastName().trim());
        u.setEmail(req.getAdmin().getEmail().trim().toLowerCase());
        u.setJobTitle(blank(req.getAdmin().getJobTitle()) ? "Organization Admin" : req.getAdmin().getJobTitle().trim());
        u.setDefaultRoleName(OWNER_ROLE);
        u.setSendWelcomeEmail(false);   // sent from the success screen, as before
        UserResponse admin = userService.createUser(u);

        // 3. First subscription — applies the plan's features to the tenant.
        SubscriptionService.SubscriptionInput in = new SubscriptionService.SubscriptionInput();
        in.planCode = plan.getCode();
        in.status = trial ? "TRIAL" : "ACTIVE";
        in.termStart = LocalDate.now();
        if (free) {
            in.termEnd = in.termStart.plusMonths(12);
            in.contractValueMinor = 0L;
            in.currency = blank(req.getCurrency()) ? "INR" : req.getCurrency().trim().toUpperCase();
            in.billingInterval = "ANNUAL";
            in.allowZeroValue = true;
        } else if (!trial) {
            int months = req.getTermMonths() == null ? 12 : req.getTermMonths();
            in.termEnd = in.termStart.plusMonths(months);
            in.contractValueMinor = toMinor(req.getContractValue());
            in.currency = blank(req.getCurrency()) ? "INR" : req.getCurrency().trim().toUpperCase();
            in.billingInterval = blank(req.getBillingInterval()) ? "ANNUAL" : req.getBillingInterval();
        }
        in.salesMotion = blank(req.getSalesMotion()) ? null : req.getSalesMotion();
        in.applyEntitlements = true;
        in.entitlementMode = "ADD_ONLY";
        TenantSubscription sub = subscriptionService.create(t.getId(), in, actorId);

        // 4. Audit-firm verification draft, when asked. Never the flag itself.
        Long firmApplicationId = null;
        if (Boolean.TRUE.equals(req.getIsAuditFirm())) {
            firmApplicationId = firmVerificationService.openDraft(t.getId(), actorId).getId();
        }

        // 5. Let other modules react inside this transaction (e.g. mark the
        //    website enquiry converted). A listener failure rolls back everything.
        events.publishEvent(new TenantProvisionedEvent(t.getId(), t.getName(), plan.getCode(),
                req.getEnquiryId(), actorId));

        Tenant saved = tenantRepository.findById(t.getId()).orElse(t);
        log.info("[TENANT] Provisioned | id={} code={} plan={} start={} admin={} firmDraft={} by={}",
                saved.getId(), code, plan.getCode(), in.status, admin.getEmail(), firmApplicationId, actorId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", saved.getId());
        out.put("tenantName", saved.getName());
        out.put("code", saved.getCode());
        out.put("status", saved.getStatus());
        out.put("plan", saved.getPlan());
        out.put("planName", plan.getName());
        out.put("maxUsers", saved.getMaxUsers());
        out.put("maxVendors", saved.getMaxVendors());
        out.put("createdAt", saved.getCreatedAt());
        out.put("subscriptionId", sub.getId());
        out.put("subscriptionStatus", sub.getStatus());
        out.put("termEnd", sub.getTermEnd());
        out.put("contractValueMinor", sub.getContractValueMinor());
        out.put("currency", sub.getCurrency());
        out.put("auditFirmApplicationId", firmApplicationId);
        out.put("admin", admin);
        return out;
    }

    // ══════════════════════ VALIDATION ═══════════════════════════════════════

    private Map<String, String> validate(TenantProvisionRequest r) {
        Map<String, String> e = new LinkedHashMap<>();
        if (blank(r.getName()) || r.getName().trim().length() < 2) e.put("name", "Organisation name is required.");
        else if (tenantRepository.existsByNameIgnoreCase(r.getName().trim()))
            e.put("name", "An organisation named \"" + r.getName().trim() + "\" already exists.");

        String code = normaliseCode(r.getCode());
        if (code.isEmpty()) {
            if (blank(r.getName())) e.put("code", "Tenant code is required.");
        } else if (!CODE.matcher(code).matches())
            e.put("code", "Use 3–20 characters: start with a letter, then letters, digits or _.");
        else if (tenantRepository.existsByCode(code))
            e.put("code", "Code " + code + " is already in use. Try " + suggestCode(code) + ".");

        if (!blank(r.getCountry()) && r.getCountry().trim().length() != 2)
            e.put("country", "Use the two-letter country code, e.g. IN.");

        TenantProvisionRequest.Admin a = r.getAdmin();
        if (a == null) a = new TenantProvisionRequest.Admin();
        if (blank(a.getFirstName())) e.put("admin.firstName", "First name is required.");
        if (blank(a.getLastName()))  e.put("admin.lastName", "Last name is required.");
        if (blank(a.getEmail()) || !a.getEmail().trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            e.put("admin.email", "A valid email is required.");
        else if (userRepository.existsByEmailAndIsDeletedFalse(a.getEmail().trim().toLowerCase())
                || userRepository.existsByEmailAndIsDeletedFalse(a.getEmail().trim()))
            e.put("admin.email", "A user with this email already exists. Each person has one KashiGRC login.");

        if (blank(r.getPlan())) e.put("plan", "Choose a plan.");
        else {
            Optional<BillingPlan> p = planRepository.findByCode(r.getPlan().trim());
            if (p.isEmpty()) e.put("plan", "Unknown plan " + r.getPlan() + ".");
            else if (!p.get().isActive()) e.put("plan", "Plan " + p.get().getName() + " is retired and cannot be sold.");
        }

        boolean freePlan = !blank(r.getPlan()) && planRepository.findByCode(r.getPlan().trim())
                .map(PublicPricingService::isFree).orElse(false);
        boolean paid = !freePlan && "ACTIVE".equalsIgnoreCase(r.getStartAs());
        if (!blank(r.getStartAs()) && !Set.of("TRIAL", "ACTIVE").contains(r.getStartAs().toUpperCase()))
            e.put("startAs", "Start as a trial or as a paid subscription.");
        if (paid) {
            if (r.getContractValue() == null || r.getContractValue().signum() <= 0)
                e.put("contractValue", "Enter the contract value for the term (it can be revised on the Subscription tab).");
            if (r.getTermMonths() != null && (r.getTermMonths() < 1 || r.getTermMonths() > 60))
                e.put("termMonths", "Term must be between 1 and 60 months.");
        }
        if (blank(r.getAcquisitionChannel()))
            e.put("acquisitionChannel", "Record how this customer found you — it cannot be reconstructed later.");
        return e;
    }

    private static BusinessException invalid(Map<String, String> fieldErrors) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fieldErrors", fieldErrors);
        String first = fieldErrors.values().iterator().next();
        return new BusinessException("TENANT_INVALID",
                fieldErrors.size() == 1 ? first : "Please fix the highlighted fields.",
                HttpStatus.UNPROCESSABLE_ENTITY, details);
    }

    // ══════════════════════ CODES ════════════════════════════════════════════

    public static String normaliseCode(String raw) {
        if (raw == null) return "";
        return raw.trim().toUpperCase().replaceAll("[\\s-]+", "_");
    }

    static String codeFromName(String name) {
        String base = name.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = "T_" + base;
        if (base.length() > 16) base = base.substring(0, 16).replaceAll("_+$", "");
        if (base.length() < 3) base = (base + "ORG").substring(0, Math.max(3, base.length()));
        return base;
    }

    /** First free code of the form BASE, BASE_2, BASE_3 … within 20 characters. */
    String suggestCode(String base) {
        String b = base.length() > 17 ? base.substring(0, 17) : base;
        if (CODE.matcher(b).matches() && !tenantRepository.existsByCode(b)) return b;
        for (int i = 2; i < 100; i++) {
            String c = b + "_" + i;
            if (!tenantRepository.existsByCode(c)) return c;
        }
        return b + "_" + System.currentTimeMillis() % 1000;
    }

    private static long toMinor(BigDecimal major) {
        return major == null ? 0L : major.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
