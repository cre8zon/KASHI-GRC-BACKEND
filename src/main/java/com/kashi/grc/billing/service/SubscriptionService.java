package com.kashi.grc.billing.service;

import com.kashi.grc.billing.domain.BillingPlan;
import com.kashi.grc.billing.domain.SubscriptionLine;
import com.kashi.grc.billing.domain.TenantSubscription;
import com.kashi.grc.billing.repository.BillingPlanRepository;
import com.kashi.grc.billing.repository.SubscriptionLineRepository;
import com.kashi.grc.billing.repository.TenantSubscriptionRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.uiconfig.domain.FeatureFlag;
import com.kashi.grc.uiconfig.repository.FeatureFlagRepository;
import com.kashi.grc.uiconfig.service.FeatureEntitlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Every write to tenant_subscriptions goes through here.
 *
 * ── THE RULES THIS CLASS EXISTS TO HOLD ───────────────────────────────────
 *  1. A contract is never edited into a different contract. Renewal, upgrade,
 *     downgrade and reactivation each write a NEW row chained by
 *     previous_subscription_id. Only notes / PO reference / auto-renew are
 *     editable in place, and money fields only while nothing has been invoiced.
 *  2. churned_at is written by markChurned and nowhere else.
 *  3. The churn rule, stated once: a customer has churned when a paid term
 *     ends with no successor term. churned_at = that term's end date. It is
 *     confirmed (status → CHURNED) after kashi.billing.churn-grace-days so a
 *     late renewal does not register as churn-then-reactivation.
 *  4. tenants.plan / max_users / max_vendors are DENORMALISED from the live
 *     subscription for fast reads. This class keeps them in step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    public static final Set<String> LIVE = Set.of("TRIAL", "ACTIVE", "PAST_DUE", "CANCELLED");
    public static final Set<String> SALES_MOTIONS = Set.of(
            "SELF_SERVE", "SALES_LED", "PARTNER", "AUDIT_FIRM_CHANNEL", "VENDOR_CHANNEL");
    public static final Set<String> CHURN_REASONS = Set.of(
            "PRICE", "NO_ADOPTION", "COMPETITOR", "BUDGET", "ACQUIRED", "OUT_OF_BUSINESS",
            "MISSING_FEATURE", "SERVICE", "OTHER");

    private final TenantSubscriptionRepository subscriptionRepository;
    private final SubscriptionLineRepository   lineRepository;
    private final BillingPlanRepository        planRepository;
    private final TenantRepository             tenantRepository;
    private final FeatureFlagRepository        featureFlagRepository;
    private final FeatureEntitlementService    featureEntitlementService;

    @Value("${kashi.billing.churn-grace-days:30}")
    private int churnGraceDays;

    // ══════════════════════ READ ═════════════════════════════════════════════

    public TenantSubscription require(Long id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", id));
    }

    /** The subscription in force for a tenant today, or empty. */
    public Optional<TenantSubscription> current(Long tenantId) {
        return subscriptionRepository.findLiveOn(tenantId, LocalDate.now()).stream().findFirst();
    }

    /** One live row per tenant on a day — the latest-starting one wins a mid-term change overlap. */
    public Map<Long, TenantSubscription> liveByTenant(LocalDate day) {
        Map<Long, TenantSubscription> out = new HashMap<>();
        for (TenantSubscription s : subscriptionRepository.findAllLiveOn(day)) {
            out.merge(s.getTenantId(), s, (a, b) -> {
                int c = b.getTermStart().compareTo(a.getTermStart());
                if (c != 0) return c > 0 ? b : a;
                return b.getId() > a.getId() ? b : a;
            });
        }
        return out;
    }

    /**
     * Monthly recurring revenue of one row, in its own currency.
     * Trials are 0. Recurring lines are used when present (one-off onboarding
     * fees are not MRR); otherwise the whole contract value is recurring.
     */
    public long mrrMinor(TenantSubscription s, List<SubscriptionLine> lines) {
        if (s == null || "TRIAL".equals(s.getStatus())) return 0L;
        long recurring;
        if (lines != null && !lines.isEmpty()) {
            recurring = lines.stream().filter(SubscriptionLine::isRecurring)
                    .mapToLong(l -> (long) l.getQuantity() * l.getUnitPriceMinor()).sum();
        } else {
            recurring = s.getContractValueMinor() == null ? 0L : s.getContractValueMinor();
        }
        return Math.max(0L, Money.monthly(recurring, s.getTermStart(), s.getTermEnd()));
    }

    public long mrrMinor(TenantSubscription s) {
        return mrrMinor(s, s == null ? List.of() : lineRepository.findBySubscriptionIdOrderByIdAsc(s.getId()));
    }

    // ══════════════════════ CREATE ═══════════════════════════════════════════

    /**
     * A tenant's first subscription, or a return after churn (REACTIVATION).
     * Refuses when a live row already exists — that is a renewal or a plan
     * change, and calling it "new" would book existing revenue as new.
     */
    @Transactional
    public TenantSubscription create(Long tenantId, SubscriptionInput in, Long actorId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        BillingPlan plan = requirePlan(in.planCode);

        if (!subscriptionRepository.findLiveOn(tenantId, in.termStart != null ? in.termStart : LocalDate.now()).isEmpty()) {
            throw new BusinessException("SUBSCRIPTION_EXISTS",
                    tenant.getName() + " already has a subscription in force. Renew it or change its plan instead.",
                    HttpStatus.CONFLICT);
        }

        boolean hadPaid = subscriptionRepository.findByTenantIdOrderByTermStartDescIdDesc(tenantId).stream()
                .anyMatch(s -> !"TRIAL".equals(s.getStatus()) && s.getContractValueMinor() != null
                        && s.getContractValueMinor() > 0);

        TenantSubscription s = build(tenantId, plan, in, actorId);
        s.setChangeType(hadPaid ? "REACTIVATION" : "NEW");
        subscriptionRepository.save(s);
        saveLines(s, in.lines);
        syncTenant(tenant, s, plan, in, actorId);

        log.info("[BILLING] Subscription created | tenant={} sub={} plan={} status={} value={} {}",
                tenantId, s.getId(), s.getPlanCode(), s.getStatus(), s.getContractValueMinor(), s.getCurrency());
        return s;
    }

    // ══════════════════════ RENEW ════════════════════════════════════════════

    /**
     * Next term, starting the day the current one ends. The old row stays live
     * until its own end date and is marked REPLACED by the daily sweep, so
     * today's MRR is unaffected by booking next year's renewal early.
     */
    @Transactional
    public TenantSubscription renew(Long subscriptionId, SubscriptionInput in, Long actorId) {
        TenantSubscription prev = require(subscriptionId);
        assertNoSuccessor(prev);
        if ("CHURNED".equals(prev.getStatus()) || "REPLACED".equals(prev.getStatus())) {
            throw new BusinessException("NOT_RENEWABLE",
                    "A " + prev.getStatus().toLowerCase() + " subscription cannot be renewed. Create a new one.",
                    HttpStatus.CONFLICT);
        }
        BillingPlan plan = requirePlan(in.planCode != null ? in.planCode : prev.getPlanCode());

        if (in.termStart == null) in.termStart = prev.getTermEnd();
        if (in.termEnd == null) {
            long months = Math.max(1, Math.round(Money.termMonths(prev.getTermStart(), prev.getTermEnd())));
            in.termEnd = in.termStart.plusMonths(months);
        }
        if (in.currency == null) in.currency = prev.getCurrency();
        if (in.billingInterval == null) in.billingInterval = prev.getBillingInterval();
        if (in.salesMotion == null) in.salesMotion = prev.getSalesMotion();
        if (in.seatsPurchased == null) in.seatsPurchased = prev.getSeatsPurchased();
        if (in.vendorsPurchased == null) in.vendorsPurchased = prev.getVendorsPurchased();
        if (in.contractValueMinor == null && (in.lines == null || in.lines.isEmpty())) {
            in.contractValueMinor = prev.getContractValueMinor();
        }
        if (in.status == null) in.status = "ACTIVE";

        TenantSubscription s = build(prev.getTenantId(), plan, in, actorId);
        s.setPreviousSubscriptionId(prev.getId());
        s.setChangeType("RENEWAL");
        subscriptionRepository.save(s);
        saveLines(s, in.lines);

        // A trial converting is a renewal of the trial into the first paid term.
        if ("TRIAL".equals(prev.getStatus()) && !in.termStart.isAfter(LocalDate.now())) {
            prev.setStatus("REPLACED");
            subscriptionRepository.save(prev);
            s.setChangeType("NEW");
            subscriptionRepository.save(s);
        }
        if (!s.getTermStart().isAfter(LocalDate.now())) {
            syncTenant(tenantOf(s), s, plan, in, actorId);
        }
        log.info("[BILLING] Renewed | prev={} next={} {}→{}", prev.getId(), s.getId(), s.getTermStart(), s.getTermEnd());
        return s;
    }

    // ══════════════════════ CHANGE PLAN (mid-term) ═══════════════════════════

    /**
     * Upgrade or downgrade from an effective date. The new row runs from that
     * date to the old term end (or a new end supplied in the input). Whether it
     * is an UPGRADE or a DOWNGRADE is decided by MRR, not by plan rank — adding
     * 50 vendors on the same plan is expansion too.
     */
    @Transactional
    public TenantSubscription changePlan(Long subscriptionId, SubscriptionInput in, Long actorId) {
        TenantSubscription prev = require(subscriptionId);
        assertNoSuccessor(prev);
        if (!LIVE.contains(prev.getStatus())) {
            throw new BusinessException("NOT_LIVE", "Only a live subscription can change plan.", HttpStatus.CONFLICT);
        }
        BillingPlan plan = requirePlan(in.planCode != null ? in.planCode : prev.getPlanCode());
        LocalDate effective = in.termStart != null ? in.termStart : LocalDate.now();
        if (!effective.isAfter(prev.getTermStart()) && !effective.isEqual(prev.getTermStart())) {
            throw new BusinessException("BAD_EFFECTIVE_DATE", "Effective date is before the current term started.");
        }
        if (!effective.isBefore(prev.getTermEnd())) {
            throw new BusinessException("BAD_EFFECTIVE_DATE",
                    "Effective date is on or after the current term end — use Renew instead.");
        }
        in.termStart = effective;
        if (in.termEnd == null) in.termEnd = prev.getTermEnd();
        if (in.currency == null) in.currency = prev.getCurrency();
        if (in.billingInterval == null) in.billingInterval = prev.getBillingInterval();
        if (in.salesMotion == null) in.salesMotion = prev.getSalesMotion();
        if (in.status == null) in.status = "TRIAL".equals(prev.getStatus()) ? "TRIAL" : "ACTIVE";

        TenantSubscription s = build(prev.getTenantId(), plan, in, actorId);
        s.setPreviousSubscriptionId(prev.getId());
        long before = mrrMinor(prev);
        long after  = mrrMinor(s, toLines(in.lines, null));
        s.setChangeType(after >= before ? "UPGRADE" : "DOWNGRADE");
        subscriptionRepository.save(s);
        saveLines(s, in.lines);

        if (!effective.isAfter(LocalDate.now())) {
            prev.setStatus("REPLACED");
            subscriptionRepository.save(prev);
            syncTenant(tenantOf(s), s, plan, in, actorId);
        }
        log.info("[BILLING] Plan change | prev={} next={} {} mrr {}→{}", prev.getId(), s.getId(),
                s.getChangeType(), before, after);
        return s;
    }

    // ══════════════════════ CANCEL / CHURN ═══════════════════════════════════

    /**
     * Customer says they are leaving. Default: runs to term end, then churns
     * through the daily sweep. immediate = true ends it today (refund cases).
     */
    @Transactional
    public TenantSubscription cancel(Long subscriptionId, String reason, String note, boolean immediate, Long actorId) {
        TenantSubscription s = require(subscriptionId);
        if (!LIVE.contains(s.getStatus())) {
            throw new BusinessException("NOT_LIVE", "Subscription is not live.", HttpStatus.CONFLICT);
        }
        if (reason != null && !CHURN_REASONS.contains(reason)) {
            throw new BusinessException("BAD_REASON", "Unknown churn reason " + reason);
        }
        s.setAutoRenew(false);
        s.setCancelledAt(LocalDateTime.now());
        s.setChurnReason(reason);
        s.setChurnNote(note);
        if ("TRIAL".equals(s.getStatus())) {
            // A trial that does not convert was never revenue; it is not churn.
            s.setStatus("EXPIRED");
            s.setTermEnd(LocalDate.now().isAfter(s.getTermStart()) ? LocalDate.now() : s.getTermStart().plusDays(1));
        } else if (immediate) {
            s.setTermEnd(LocalDate.now().isAfter(s.getTermStart()) ? LocalDate.now() : s.getTermStart().plusDays(1));
            markChurned(s, s.getTermEnd(), reason, note);
        } else {
            s.setStatus("CANCELLED");
        }
        subscriptionRepository.save(s);
        return s;
    }

    /** THE single writer of churned_at. */
    @Transactional
    public void markChurned(TenantSubscription s, LocalDate churnedOn, String reason, String note) {
        s.setStatus("CHURNED");
        s.setChurnedAt(churnedOn);
        if (reason != null) s.setChurnReason(reason);
        if (note != null && s.getChurnNote() == null) s.setChurnNote(note);
        subscriptionRepository.save(s);
        log.info("[BILLING] Churned | tenant={} sub={} on={} reason={}",
                s.getTenantId(), s.getId(), churnedOn, s.getChurnReason());
    }

    /**
     * Daily sweep: rows whose term has ended.
     *   has a successor              → REPLACED, successor synced onto the tenant
     *   TRIAL                        → EXPIRED (not churn — it never paid)
     *   auto-renew and ACTIVE        → renewed for the same length at the same value
     *   otherwise, past grace        → churned on its term end
     */
    @Transactional
    public Map<String, Integer> sweepLapsed(LocalDate today) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TenantSubscription s : subscriptionRepository.findLapsed(today)) {
            Optional<TenantSubscription> next = subscriptionRepository.findFirstByPreviousSubscriptionId(s.getId());
            if (next.isPresent()) {
                s.setStatus("REPLACED");
                subscriptionRepository.save(s);
                TenantSubscription n = next.get();
                planRepository.findByCode(n.getPlanCode()).ifPresent(p ->
                        syncTenant(tenantOf(n), n, p, null, null));
                counts.merge("replaced", 1, Integer::sum);
            } else if ("TRIAL".equals(s.getStatus())) {
                s.setStatus("EXPIRED");
                subscriptionRepository.save(s);
                counts.merge("trialExpired", 1, Integer::sum);
            } else if (s.isAutoRenew() && ("ACTIVE".equals(s.getStatus()) || "PAST_DUE".equals(s.getStatus()))) {
                SubscriptionInput in = new SubscriptionInput();
                in.status = "ACTIVE";
                List<SubscriptionLine> lines = lineRepository.findBySubscriptionIdOrderByIdAsc(s.getId());
                if (!lines.isEmpty()) {
                    in.lines = lines.stream().filter(SubscriptionLine::isRecurring).map(LineInput::of).toList();
                }
                renew(s.getId(), in, null);
                s.setStatus("REPLACED");
                subscriptionRepository.save(s);
                counts.merge("autoRenewed", 1, Integer::sum);
            } else if (!s.getTermEnd().plusDays(churnGraceDays).isAfter(today)) {
                markChurned(s, s.getTermEnd(), s.getChurnReason(), null);
                counts.merge("churned", 1, Integer::sum);
            } else {
                counts.merge("inGrace", 1, Integer::sum);
            }
        }
        return counts;
    }

    // ══════════════════════ EDIT IN PLACE ════════════════════════════════════

    /** Only fields that do not change what the contract IS. */
    @Transactional
    public TenantSubscription updateAdministrative(Long id, Map<String, Object> body) {
        TenantSubscription s = require(id);
        if (body.containsKey("notes"))       s.setNotes(str(body.get("notes")));
        if (body.containsKey("poReference")) s.setPoReference(str(body.get("poReference")));
        if (body.containsKey("autoRenew"))   s.setAutoRenew(Boolean.TRUE.equals(body.get("autoRenew")));
        if (body.containsKey("salesMotion")) {
            String m = str(body.get("salesMotion"));
            if (m != null && !SALES_MOTIONS.contains(m)) throw new BusinessException("BAD_MOTION", "Unknown sales motion " + m);
            s.setSalesMotion(m);
        }
        if (body.containsKey("status")) {
            String st = str(body.get("status"));
            // Only the payment-state toggle is manual. Everything else has its own verb.
            if (!Set.of("ACTIVE", "PAST_DUE").contains(st) || !Set.of("ACTIVE", "PAST_DUE").contains(s.getStatus())) {
                throw new BusinessException("BAD_STATUS",
                        "Status can only be toggled between ACTIVE and PAST_DUE here. Use renew, change plan or cancel.");
            }
            s.setStatus(st);
        }
        return subscriptionRepository.save(s);
    }

    // ══════════════════════ INTERNALS ════════════════════════════════════════

    private TenantSubscription build(Long tenantId, BillingPlan plan, SubscriptionInput in, Long actorId) {
        LocalDate start = in.termStart != null ? in.termStart : LocalDate.now();
        String status = in.status != null ? in.status : "ACTIVE";
        if (!Set.of("TRIAL", "ACTIVE").contains(status)) {
            throw new BusinessException("BAD_STATUS", "A new term starts as TRIAL or ACTIVE.");
        }
        LocalDate end = in.termEnd != null ? in.termEnd
                : "TRIAL".equals(status) ? start.plusDays(plan.getTrialDays() != null ? plan.getTrialDays() : 30)
                : start.plusYears(1);
        if (!end.isAfter(start)) throw new BusinessException("BAD_TERM", "Term end must be after term start.");

        String motion = in.salesMotion;
        if (motion != null && !SALES_MOTIONS.contains(motion)) {
            throw new BusinessException("BAD_MOTION", "Unknown sales motion " + motion);
        }
        String currency = in.currency != null ? in.currency.toUpperCase() : "INR";
        if (currency.length() != 3) throw new BusinessException("BAD_CURRENCY", "Currency must be a 3-letter ISO code.");

        long value;
        if (in.lines != null) {
            for (LineInput l : in.lines) {
                if (l.unitPriceMinor == null) throw new BusinessException("BAD_LINE", "Every line needs a unit price.");
                if (l.quantity != null && l.quantity < 1) throw new BusinessException("BAD_LINE", "Line quantity must be at least 1.");
            }
        }
        if (in.lines != null && !in.lines.isEmpty()) {
            value = in.lines.stream().mapToLong(l -> (long) (l.quantity != null ? l.quantity : 1) * l.unitPriceMinor).sum();
        } else {
            value = in.contractValueMinor != null ? in.contractValueMinor : 0L;
        }
        if ("TRIAL".equals(status)) value = 0L;
        if (value < 0) throw new BusinessException("BAD_VALUE", "Contract value cannot be negative.");
        if ("ACTIVE".equals(status) && value == 0L && !Boolean.TRUE.equals(in.allowZeroValue)) {
            throw new BusinessException("ZERO_VALUE",
                    "An ACTIVE subscription with no value would count as a paying customer at ₹0. "
                            + "Use TRIAL, or confirm a complimentary account explicitly.");
        }

        return TenantSubscription.builder()
                .tenantId(tenantId)
                .planCode(plan.getCode())
                .status(status)
                .termStart(start)
                .termEnd(end)
                .seatsPurchased(in.seatsPurchased != null ? in.seatsPurchased : plan.getIncludedUsers())
                .vendorsPurchased(in.vendorsPurchased != null ? in.vendorsPurchased : plan.getIncludedVendors())
                .contractValueMinor(value)
                .currency(currency)
                .billingInterval(in.billingInterval != null ? in.billingInterval : "ANNUAL")
                .autoRenew(in.autoRenew == null || in.autoRenew)
                .salesMotion(motion)
                .poReference(in.poReference)
                .notes(in.notes)
                .createdBy(actorId)
                .build();
    }

    private void saveLines(TenantSubscription s, List<LineInput> lines) {
        if (lines == null || lines.isEmpty()) {
            // Record the plan itself as the single line so "which modules earn"
            // always has at least the base row to aggregate.
            if (s.getContractValueMinor() != null && s.getContractValueMinor() > 0) {
                lineRepository.save(SubscriptionLine.builder()
                        .subscriptionId(s.getId()).lineType("PLAN").itemCode(s.getPlanCode())
                        .description(s.getPlanCode() + " plan").quantity(1)
                        .unitPriceMinor(s.getContractValueMinor()).isRecurring(true).build());
            }
            return;
        }
        lineRepository.saveAll(toLines(lines, s.getId()));
    }

    private List<SubscriptionLine> toLines(List<LineInput> lines, Long subscriptionId) {
        if (lines == null) return List.of();
        return lines.stream().map(l -> SubscriptionLine.builder()
                .subscriptionId(subscriptionId)
                .lineType(l.lineType != null ? l.lineType : "PLAN")
                .itemCode(l.itemCode)
                .description(l.description)
                .quantity(l.quantity != null ? l.quantity : 1)
                .unitPriceMinor(l.unitPriceMinor)
                .isRecurring(l.recurring == null || l.recurring)
                .build()).collect(Collectors.toList());
    }

    /**
     * Keeps the tenants row in step with the subscription in force.
     * Suspension is an operational decision and is never undone from here.
     */
    private void syncTenant(Tenant t, TenantSubscription s, BillingPlan plan, SubscriptionInput in, Long actorId) {
        if (t == null) return;
        t.setPlan(s.getPlanCode());
        t.setMaxUsers(s.getSeatsPurchased());
        t.setMaxVendors(s.getVendorsPurchased());
        if (!"SUSPENDED".equals(t.getStatus())) {
            t.setStatus("TRIAL".equals(s.getStatus()) ? "TRIAL" : "ACTIVE");
        }
        if (t.getFirstPaidAt() == null && !"TRIAL".equals(s.getStatus())
                && s.getContractValueMinor() != null && s.getContractValueMinor() > 0) {
            t.setFirstPaidAt(s.getTermStart());
        }
        if (t.getAcquisitionChannel() == null && s.getSalesMotion() != null) {
            // A sensible default only — the profile field is the real record.
            t.setAcquisitionChannel(switch (s.getSalesMotion()) {
                case "AUDIT_FIRM_CHANNEL", "VENDOR_CHANNEL" -> s.getSalesMotion();
                case "PARTNER" -> "PARTNER";
                case "SELF_SERVE" -> "INBOUND";
                default -> null;
            });
        }
        tenantRepository.save(t);

        String mode = in != null && in.entitlementMode != null ? in.entitlementMode : "ADD_ONLY";
        if (in != null && Boolean.FALSE.equals(in.applyEntitlements)) return;
        applyEntitlements(t.getId(), plan, mode, actorId);
    }

    /**
     * Applies the plan's LICENSED feature keys through FeatureEntitlementService.
     *
     * Keys are checked against the catalogue FIRST. setTenant throws for a
     * GLOBAL key and is itself @Transactional, so letting it throw inside this
     * transaction would mark the whole subscription write rollback-only even if
     * the exception were caught.
     *
     *   ADD_ONLY  grant what the plan includes, touch nothing else (default:
     *             a manually granted extra is never silently taken away)
     *   EXACT     also revoke LICENSED features the plan does not include
     *             (use on a deliberate downgrade)
     */
    public Map<String, List<String>> applyEntitlements(Long tenantId, BillingPlan plan, String mode, Long actorId) {
        Set<String> wanted = plan.getFeatureKeysCsv() == null ? Set.of()
                : Arrays.stream(plan.getFeatureKeysCsv().split(","))
                    .map(String::trim).filter(k -> !k.isEmpty()).collect(Collectors.toSet());

        Set<String> licensed = featureFlagRepository.findByTenantIdIsNull().stream()
                .filter(f -> f.getDeletedAt() == null && "LICENSED".equals(f.getMode()))
                .map(FeatureFlag::getFlagKey).collect(Collectors.toSet());

        Set<String> enabledNow = featureFlagRepository.findByTenantId(tenantId).stream()
                .filter(f -> f.getDeletedAt() == null && f.isEnabled())
                .map(FeatureFlag::getFlagKey).collect(Collectors.toSet());

        List<String> granted = new ArrayList<>(), revoked = new ArrayList<>(), skipped = new ArrayList<>();
        for (String k : wanted) {
            if (!licensed.contains(k)) { skipped.add(k); continue; }
            if (!enabledNow.contains(k)) {
                featureEntitlementService.setTenant(k, tenantId, true, actorId);
                granted.add(k);
            }
        }
        if ("EXACT".equals(mode)) {
            for (String k : enabledNow) {
                if (licensed.contains(k) && !wanted.contains(k)) {
                    featureEntitlementService.setTenant(k, tenantId, false, actorId);
                    revoked.add(k);
                }
            }
        }
        if (!skipped.isEmpty()) {
            log.warn("[BILLING] Plan {} lists non-LICENSED keys {} — not applied", plan.getCode(), skipped);
        }
        return Map.of("granted", granted, "revoked", revoked, "skipped", skipped);
    }

    private void assertNoSuccessor(TenantSubscription s) {
        subscriptionRepository.findFirstByPreviousSubscriptionId(s.getId()).ifPresent(n -> {
            throw new BusinessException("ALREADY_SUCCEEDED",
                    "Subscription #" + s.getId() + " already has a successor (#" + n.getId() + "). Act on that one.",
                    HttpStatus.CONFLICT);
        });
    }

    private BillingPlan requirePlan(String code) {
        if (code == null || code.isBlank()) throw new BusinessException("PLAN_REQUIRED", "Plan is required.");
        BillingPlan p = planRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException("UNKNOWN_PLAN", "No plan with code " + code));
        if (!p.isActive()) throw new BusinessException("PLAN_RETIRED", "Plan " + code + " is retired and cannot be sold.");
        return p;
    }

    private Tenant tenantOf(TenantSubscription s) {
        return tenantRepository.findById(s.getTenantId()).orElse(null);
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    // ══════════════════════ INPUT ════════════════════════════════════════════

    /** Shared by create / renew / change-plan. Nulls mean "default" or "carry over". */
    @lombok.Data
    public static class SubscriptionInput {
        public String planCode;
        public String status;              // TRIAL | ACTIVE
        public LocalDate termStart;        // change-plan: the effective date
        public LocalDate termEnd;
        public Integer seatsPurchased;
        public Integer vendorsPurchased;
        public Long contractValueMinor;    // ignored when lines are given
        public String currency;
        public String billingInterval;
        public Boolean autoRenew;
        public String salesMotion;
        public String poReference;
        public String notes;
        public List<LineInput> lines;
        public Boolean applyEntitlements;  // default true
        public String entitlementMode;     // ADD_ONLY | EXACT
        public Boolean allowZeroValue;     // complimentary account, explicitly
    }

    @lombok.Data
    public static class LineInput {
        public String lineType;
        public String itemCode;
        public String description;
        public Integer quantity;
        public Long unitPriceMinor;
        public Boolean recurring;

        static LineInput of(SubscriptionLine l) {
            LineInput i = new LineInput();
            i.lineType = l.getLineType();
            i.itemCode = l.getItemCode();
            i.description = l.getDescription();
            i.quantity = l.getQuantity();
            i.unitPriceMinor = l.getUnitPriceMinor();
            i.recurring = l.isRecurring();
            return i;
        }
    }
}
