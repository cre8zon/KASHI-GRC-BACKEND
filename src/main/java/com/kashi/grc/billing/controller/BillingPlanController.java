package com.kashi.grc.billing.controller;

import com.kashi.grc.billing.domain.BillingFeature;
import com.kashi.grc.billing.domain.BillingPlan;
import com.kashi.grc.billing.domain.BillingPlanPrice;
import com.kashi.grc.billing.repository.BillingFeatureRepository;
import com.kashi.grc.billing.repository.BillingPlanPriceRepository;
import com.kashi.grc.billing.repository.BillingPlanRepository;
import com.kashi.grc.billing.service.FxService;
import com.kashi.grc.billing.service.PublicPricingService;
import com.kashi.grc.billing.service.SubscriptionService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.uiconfig.repository.FeatureFlagRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import com.kashi.grc.billing.service.BillingAccess;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Plan catalogue. Under /v1/admin/** — SecurityConfig already restricts the
 * whole prefix to SIDE_SYSTEM, so no annotation is needed for the boundary.
 *
 * Plans are never deleted: subscriptions point at them by code. Retire with
 * isActive = false.
 */
@RestController
@RequestMapping("/v1/admin/billing")
@PreAuthorize(BillingAccess.VIEW)
@Tag(name = "Billing — Plans")
@RequiredArgsConstructor
public class BillingPlanController {

    private final BillingPlanRepository      planRepository;
    private final BillingPlanPriceRepository priceRepository;
    private final FeatureFlagRepository      featureFlagRepository;
    private final FxService                  fx;
    private final BillingFeatureRepository   featureRepository;
    private final PublicPricingService       publicPricing;

    static final Set<String> AUDIENCES      = Set.of("ORGANISATION", "AUDIT_FIRM");
    static final Set<String> PRICE_DISPLAYS = Set.of("FIXED", "STARTING_AT", "CONTACT_SALES", "FREE");
    static final Set<String> CTA_TYPES      = Set.of("TRIAL", "DEMO", "CONTACT_SALES", "APPLY");

    @GetMapping("/plans")
    @Operation(summary = "Plan catalogue with prices")
    public ApiResponse<List<Map<String, Object>>> list() {
        List<BillingPlan> plans = planRepository.findAllByOrderBySortOrderAscTierRankAsc();
        Map<Long, List<BillingPlanPrice>> prices = priceRepository
                .findByPlanIdIn(plans.stream().map(BillingPlan::getId).toList())
                .stream().collect(Collectors.groupingBy(BillingPlanPrice::getPlanId));
        return ApiResponse.success(plans.stream()
                .map(p -> BillingViews.plan(p, prices.getOrDefault(p.getId(), List.of()))).toList());
    }

    @PostMapping("/plans")
    @PreAuthorize(BillingAccess.MANAGE)
    @Transactional
    @Operation(summary = "Create a plan")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String code = req(body, "code").toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (planRepository.existsByCode(code)) {
            throw new BusinessException("PLAN_CODE_TAKEN", "A plan with code " + code + " exists.", HttpStatus.CONFLICT);
        }
        BillingPlan p = BillingPlan.builder().code(code).name(req(body, "name")).build();
        apply(p, body);
        planRepository.save(p);
        savePrices(p.getId(), body.get("prices"));
        publicPricing.evict();
        return ApiResponse.success(BillingViews.plan(p, priceRepository.findByPlanId(p.getId())));
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize(BillingAccess.MANAGE)
    @Transactional
    @Operation(summary = "Update a plan (code is immutable)")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        BillingPlan p = planRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Plan", id));
        if (body.containsKey("name")) p.setName(req(body, "name"));
        apply(p, body);
        planRepository.save(p);
        if (body.containsKey("prices")) savePrices(p.getId(), body.get("prices"));
        publicPricing.evict();
        return ApiResponse.success(BillingViews.plan(p, priceRepository.findByPlanId(p.getId())));
    }

    @GetMapping("/config")
    @Operation(summary = "Vocabularies, reporting currency, FX rates and LICENSED feature keys for billing forms")
    public ApiResponse<Map<String, Object>> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportingCurrency", fx.reportingCurrency());
        m.put("fxRates", fx.rates());
        m.put("salesMotions", SubscriptionService.SALES_MOTIONS.stream().sorted().toList());
        m.put("churnReasons", SubscriptionService.CHURN_REASONS.stream().sorted().toList());
        m.put("acquisitionChannels", List.of("OUTBOUND", "INBOUND", "REFERRAL", "PARTNER",
                "AUDIT_FIRM_CHANNEL", "VENDOR_CHANNEL", "EVENTS", "PAID_ADS", "OTHER"));
        m.put("costTypes", List.of("SALARY", "COMMISSION", "ADS", "EVENTS", "TOOLING", "FOUNDER_TIME", "AGENCY", "OTHER"));
        m.put("billingIntervals", List.of("MONTHLY", "QUARTERLY", "ANNUAL", "MULTI_YEAR"));
        m.put("pricingMetrics", List.of("VENDORS", "SEATS", "ENTITIES", "FLAT"));
        m.put("lineTypes", List.of("PLAN", "ADDON", "VENDOR_PACK", "AI_CREDITS", "SERVICE", "DISCOUNT"));
        m.put("audiences", List.of("ORGANISATION", "AUDIT_FIRM"));
        m.put("priceDisplays", List.of("STARTING_AT", "FIXED", "CONTACT_SALES", "FREE"));
        m.put("ctaTypes", List.of("TRIAL", "DEMO", "CONTACT_SALES", "APPLY"));
        m.put("licensedFeatures", featureFlagRepository.findByTenantIdIsNull().stream()
                .filter(f -> f.getDeletedAt() == null && "LICENSED".equals(f.getMode()))
                .map(f -> Map.of("flagKey", f.getFlagKey(),
                        "description", f.getDescription() == null ? "" : f.getDescription()))
                .toList());
        return ApiResponse.success(m);
    }

    // ── Feature catalogue (labels for the public comparison table) ──────────

    @GetMapping("/features")
    @Operation(summary = "Feature catalogue — buyer-facing labels for plan feature keys")
    public ApiResponse<List<Map<String, Object>>> features() {
        return ApiResponse.success(featureRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(BillingPlanController::featureView).toList());
    }

    @PostMapping("/features")
    @PreAuthorize(BillingAccess.MANAGE)
    @Transactional
    @Operation(summary = "Add a catalogue row")
    public ApiResponse<Map<String, Object>> createFeature(@RequestBody Map<String, Object> body) {
        String key = req(body, "featureKey").trim();
        if (featureRepository.existsByFeatureKey(key)) {
            throw new BusinessException("FEATURE_KEY_TAKEN", "A catalogue row for " + key + " exists.", HttpStatus.CONFLICT);
        }
        BillingFeature f = BillingFeature.builder().featureKey(key).label(req(body, "label")).build();
        applyFeature(f, body);
        featureRepository.save(f);
        publicPricing.evict();
        return ApiResponse.success(featureView(f));
    }

    @PutMapping("/features/{id}")
    @PreAuthorize(BillingAccess.MANAGE)
    @Transactional
    @Operation(summary = "Update a catalogue row (feature key is immutable)")
    public ApiResponse<Map<String, Object>> updateFeature(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        BillingFeature f = featureRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("BillingFeature", id));
        if (body.containsKey("label")) f.setLabel(req(body, "label"));
        applyFeature(f, body);
        featureRepository.save(f);
        publicPricing.evict();
        return ApiResponse.success(featureView(f));
    }

    /**
     * Removes the label only. Plans that list the key keep it, and so do the
     * tenants it was granted to — this table is presentation, not entitlement.
     */
    @DeleteMapping("/features/{id}")
    @PreAuthorize(BillingAccess.MANAGE)
    @Transactional
    @Operation(summary = "Remove a catalogue row (does not touch plans or tenants)")
    public ApiResponse<Map<String, Object>> deleteFeature(@PathVariable Long id) {
        featureRepository.findById(id).ifPresent(featureRepository::delete);
        publicPricing.evict();
        return ApiResponse.success(Map.of("deleted", id));
    }

    /**
     * Matrix edit: switch one feature key on or off for one plan. The same
     * feature_keys_csv the plan editor writes, so it drives entitlements for
     * NEW subscriptions — existing tenants are re-synced from the subscription
     * (Apply entitlements), never silently from here.
     */
    @PutMapping("/plans/{id}/features/{featureKey}")
    @PreAuthorize(BillingAccess.MANAGE)
    @Transactional
    @Operation(summary = "Include or exclude one feature key in a plan")
    public ApiResponse<Map<String, Object>> setPlanFeature(@PathVariable Long id, @PathVariable String featureKey,
                                                           @RequestBody Map<String, Object> body) {
        BillingPlan p = planRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Plan", id));
        LinkedHashSet<String> keys = p.getFeatureKeysCsv() == null ? new LinkedHashSet<>()
                : Arrays.stream(p.getFeatureKeysCsv().split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        if (Boolean.TRUE.equals(body.get("included"))) keys.add(featureKey.trim()); else keys.remove(featureKey.trim());
        p.setFeatureKeysCsv(String.join(",", keys));
        planRepository.save(p);
        publicPricing.evict();
        return ApiResponse.success(BillingViews.plan(p, priceRepository.findByPlanId(p.getId())));
    }

    private void applyFeature(BillingFeature f, Map<String, Object> b) {
        if (b.containsKey("description"))    f.setDescription(str(b.get("description")));
        if (b.containsKey("groupName"))      f.setGroupName(Optional.ofNullable(str(b.get("groupName"))).orElse("Platform"));
        if (b.containsKey("sortOrder"))      f.setSortOrder(intOr(b.get("sortOrder"), 0));
        if (b.containsKey("includedInAll"))  f.setIncludedInAll(Boolean.TRUE.equals(b.get("includedInAll")));
        if (b.containsKey("addonAvailable")) f.setAddonAvailable(Boolean.TRUE.equals(b.get("addonAvailable")));
        if (b.containsKey("isPublic"))       f.setPublic(Boolean.TRUE.equals(b.get("isPublic")));
    }

    private static Map<String, Object> featureView(BillingFeature f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("featureKey", f.getFeatureKey());
        m.put("label", f.getLabel());
        m.put("description", f.getDescription());
        m.put("groupName", f.getGroupName());
        m.put("sortOrder", f.getSortOrder());
        m.put("includedInAll", f.isIncludedInAll());
        m.put("addonAvailable", f.isAddonAvailable());
        m.put("isPublic", f.isPublic());
        return m;
    }

    // ── internals ───────────────────────────────────────────────────────────

    private void apply(BillingPlan p, Map<String, Object> b) {
        if (b.containsKey("tagline"))          p.setTagline(str(b.get("tagline")));
        if (b.containsKey("description"))      p.setDescription(str(b.get("description")));
        if (b.containsKey("tierRank"))         p.setTierRank(intOr(b.get("tierRank"), 1));
        if (b.containsKey("pricingMetric")) {
            String m = str(b.get("pricingMetric"));
            if (!Set.of("VENDORS", "SEATS", "ENTITIES", "FLAT").contains(m)) {
                throw new BusinessException("BAD_METRIC", "pricingMetric must be VENDORS, SEATS, ENTITIES or FLAT");
            }
            p.setPricingMetric(m);
        }
        if (b.containsKey("includedVendors"))  p.setIncludedVendors(intOrNull(b.get("includedVendors")));
        if (b.containsKey("includedUsers"))    p.setIncludedUsers(intOrNull(b.get("includedUsers")));
        if (b.containsKey("includedEntities")) p.setIncludedEntities(intOrNull(b.get("includedEntities")));
        if (b.containsKey("aiMonthlyTokens"))  p.setAiMonthlyTokens(longOrNull(b.get("aiMonthlyTokens")));
        if (b.containsKey("featureKeys")) {
            Object v = b.get("featureKeys");
            String csv = v instanceof Collection<?> c
                    ? c.stream().map(Object::toString).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.joining(","))
                    : str(v);
            p.setFeatureKeysCsv(csv);
        }
        if (b.containsKey("highlights")) {
            Object v = b.get("highlights");
            p.setHighlights(v instanceof Collection<?> c
                    ? c.stream().map(Object::toString).collect(Collectors.joining("\n")) : str(v));
        }
        if (b.containsKey("supportLevel")) p.setSupportLevel(str(b.get("supportLevel")));
        if (b.containsKey("isPublic"))     p.setPublic(Boolean.TRUE.equals(b.get("isPublic")));
        if (b.containsKey("isActive"))     p.setActive(Boolean.TRUE.equals(b.get("isActive")));
        if (b.containsKey("trialDays"))    p.setTrialDays(intOr(b.get("trialDays"), 30));
        if (b.containsKey("sortOrder"))    p.setSortOrder(intOr(b.get("sortOrder"), 0));
        // Public price list presentation
        if (b.containsKey("audience"))           p.setAudience(oneOf(b.get("audience"), AUDIENCES, "ORGANISATION", "audience"));
        if (b.containsKey("priceDisplay"))       p.setPriceDisplay(oneOf(b.get("priceDisplay"), PRICE_DISPLAYS, "CONTACT_SALES", "priceDisplay"));
        if (b.containsKey("ctaType"))            p.setCtaType(oneOf(b.get("ctaType"), CTA_TYPES, "DEMO", "ctaType"));
        if (b.containsKey("ctaLabel"))           p.setCtaLabel(str(b.get("ctaLabel")));
        if (b.containsKey("badge"))              p.setBadge(str(b.get("badge")));
        if (b.containsKey("includedFrameworks")) p.setIncludedFrameworks(intOrNull(b.get("includedFrameworks")));
        if (b.containsKey("publicNote"))         p.setPublicNote(str(b.get("publicNote")));
    }

    private static String oneOf(Object raw, Set<String> allowed, String dflt, String field) {
        String v = str(raw);
        if (v == null) return dflt;
        v = v.toUpperCase();
        if (!allowed.contains(v)) throw new BusinessException("BAD_" + field.toUpperCase(), field + " must be one of " + new TreeSet<>(allowed));
        return v;
    }

    /** Replaces the plan's price set. One row per (currency, interval). */
    @SuppressWarnings("unchecked")
    private void savePrices(Long planId, Object raw) {
        if (!(raw instanceof Collection<?> list)) return;
        Map<String, BillingPlanPrice> existing = priceRepository.findByPlanId(planId).stream()
                .collect(Collectors.toMap(x -> x.getCurrency() + "|" + x.getBillingInterval(), x -> x));
        Set<String> seen = new HashSet<>();
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            String cur = req(m, "currency").toUpperCase();
            String interval = req(m, "billingInterval").toUpperCase();
            if (cur.length() != 3) throw new BusinessException("BAD_CURRENCY", "Currency must be ISO 4217.");
            if (!Set.of("MONTHLY", "ANNUAL").contains(interval)) {
                throw new BusinessException("BAD_INTERVAL", "List prices are MONTHLY or ANNUAL.");
            }
            String k = cur + "|" + interval;
            if (!seen.add(k)) throw new BusinessException("DUP_PRICE", "Duplicate price for " + k);
            Long amount = longOrNull(m.get("unitAmountMinor"));
            if (amount == null || amount < 0) throw new BusinessException("BAD_AMOUNT", "unitAmountMinor is required and ≥ 0.");
            BillingPlanPrice x = existing.getOrDefault(k,
                    BillingPlanPrice.builder().planId(planId).currency(cur).billingInterval(interval).build());
            x.setUnitAmountMinor(amount);
            x.setOverageUnitMinor(longOrNull(m.get("overageUnitMinor")));
            x.setAiOveragePerMillionMinor(longOrNull(m.get("aiOveragePerMillionMinor")));
            x.setActive(!Boolean.FALSE.equals(m.get("isActive")));
            priceRepository.save(x);
        }
        existing.forEach((k, x) -> { if (!seen.contains(k)) priceRepository.delete(x); });
    }

    private static String req(Map<String, Object> b, String k) {
        String v = str(b.get(k));
        if (v == null) throw new BusinessException("FIELD_REQUIRED", k + " is required.");
        return v;
    }
    private static String str(Object o) { if (o == null) return null; String s = o.toString().trim(); return s.isEmpty() ? null : s; }
    private static Integer intOrNull(Object o) { String s = str(o); return s == null ? null : (int) Double.parseDouble(s); }
    private static int intOr(Object o, int d) { Integer v = intOrNull(o); return v == null ? d : v; }
    private static Long longOrNull(Object o) { String s = str(o); return s == null ? null : (long) Double.parseDouble(s); }
}
