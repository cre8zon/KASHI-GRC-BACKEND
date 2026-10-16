package com.kashi.grc.billing.service;

import com.kashi.grc.billing.domain.BillingFeature;
import com.kashi.grc.billing.domain.BillingPlan;
import com.kashi.grc.billing.domain.BillingPlanPrice;
import com.kashi.grc.billing.repository.BillingFeatureRepository;
import com.kashi.grc.billing.repository.BillingPlanPriceRepository;
import com.kashi.grc.billing.repository.BillingPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The public price list — what www.kashiguard.com/pricing renders.
 *
 * ── ONE SOURCE ───────────────────────────────────────────────────────────────
 * Everything here is read from billing_plans, billing_plan_prices and
 * billing_features. The website keeps no copy, so the card a prospect reads,
 * the plan card in Create Tenant and the subscription a tenant ends up on are
 * the same row.
 *
 * ── WHAT IS PUBLISHED ────────────────────────────────────────────────────────
 * Plans with is_public AND is_active only. Prices only when the plan's
 * price_display asks for a number (FIXED / STARTING_AT) — a CONTACT_SALES plan
 * may well have a list price in the catalogue for the sales team, and it stays
 * internal. Internal fields (description, sort order, overage, ids) never
 * leave this class.
 *
 * ── CARD BULLETS ARE DERIVED ─────────────────────────────────────────────────
 * "Everything in Starter, plus …" is computed: the public feature rows a plan
 * has that the previous plan of the same audience does not. Adding a module to
 * Growth in the admin adds the bullet; nobody edits website copy.
 *
 * Cached per currency for five minutes; the admin endpoints call evict() on
 * every plan or feature change so an edit shows on the next page load.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicPricingService {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final BillingPlanRepository      planRepository;
    private final BillingPlanPriceRepository priceRepository;
    private final BillingFeatureRepository   featureRepository;

    private record Cached(Map<String, Object> body, Instant at) {}
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public void evict() { cache.clear(); }

    @Transactional(readOnly = true)
    public Map<String, Object> pricing(String rawCurrency) {
        String currency = rawCurrency == null || rawCurrency.isBlank() ? "INR" : rawCurrency.trim().toUpperCase();
        Cached c = cache.get(currency);
        if (c != null && c.at().plus(TTL).isAfter(Instant.now())) return c.body();
        Map<String, Object> body = build(currency);
        cache.put(currency, new Cached(body, Instant.now()));
        return body;
    }

    // ── build ────────────────────────────────────────────────────────────────

    private Map<String, Object> build(String currency) {
        List<BillingPlan> plans = planRepository.findAllByOrderBySortOrderAscTierRankAsc().stream()
                .filter(p -> p.isActive() && p.isPublic())
                .sorted(Comparator.comparing((BillingPlan p) -> audienceOf(p).equals("AUDIT_FIRM") ? 1 : 0)
                        .thenComparing(p -> p.getTierRank() == null ? 0 : p.getTierRank())
                        .thenComparing(p -> p.getSortOrder() == null ? 0 : p.getSortOrder()))
                .toList();

        Map<Long, List<BillingPlanPrice>> prices = plans.isEmpty() ? Map.of()
                : priceRepository.findByPlanIdIn(plans.stream().map(BillingPlan::getId).toList()).stream()
                    .filter(BillingPlanPrice::isActive)
                    .collect(Collectors.groupingBy(BillingPlanPrice::getPlanId));

        List<BillingFeature> features = featureRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(BillingFeature::isPublic).toList();

        List<BillingPlan> orgPlans = plans.stream().filter(p -> "ORGANISATION".equals(audienceOf(p))).toList();

        List<Map<String, Object>> planViews = new ArrayList<>();
        Map<String, BillingPlan> previousOf = new HashMap<>();
        BillingPlan prevOrg = null;
        for (BillingPlan p : plans) {
            if ("ORGANISATION".equals(audienceOf(p))) {
                if (prevOrg != null) previousOf.put(p.getCode(), prevOrg);
                prevOrg = p;
            }
        }
        for (BillingPlan p : plans) {
            planViews.add(planView(p, prices.getOrDefault(p.getId(), List.of()), currency, features, previousOf.get(p.getCode())));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", currency);
        out.put("generatedAt", Instant.now().toString());
        out.put("plans", planViews);
        out.put("comparison", comparison(orgPlans, features));
        return out;
    }

    private Map<String, Object> planView(BillingPlan p, List<BillingPlanPrice> prices, String currency,
                                         List<BillingFeature> features, BillingPlan previous) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.getCode());
        m.put("name", p.getName());
        m.put("tagline", p.getTagline());
        m.put("audience", audienceOf(p));
        m.put("badge", blankToNull(p.getBadge()));

        String display = displayOf(p);
        m.put("priceDisplay", display);
        m.put("price", priceView(display, prices, currency));
        m.put("publicNote", blankToNull(p.getPublicNote()));

        String cta = p.getCtaType() == null ? "DEMO" : p.getCtaType();
        m.put("cta", Map.of("type", cta, "label", blank(p.getCtaLabel()) ? defaultCtaLabel(cta) : p.getCtaLabel()));

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("vendors", p.getIncludedVendors());          // null = unlimited
        limits.put("seats", p.getIncludedUsers());
        limits.put("frameworks", p.getIncludedFrameworks());
        limits.put("entities", p.getIncludedEntities());
        limits.put("aiMonthlyTokens", p.getAiMonthlyTokens());
        m.put("limits", limits);
        m.put("trialDays", p.getTrialDays());
        m.put("support", supportLabel(p.getSupportLevel()));
        m.put("highlights", lines(p.getHighlights()));

        Set<String> keys = keys(p);
        Set<String> prevKeys = previous == null ? Set.of() : keys(previous);
        boolean org = "ORGANISATION".equals(audienceOf(p));
        m.put("inherits", previous == null ? null : Map.of("code", previous.getCode(), "name", previous.getName()));
        // Card bullets: modules this plan adds over the previous tier. The entry
        // tier also lists the core rows, so its card is not empty.
        m.put("features", features.stream()
                .filter(f -> org && (keys.contains(f.getFeatureKey()) || (previous == null && f.isIncludedInAll()))
                        && !prevKeys.contains(f.getFeatureKey()))
                .map(BillingFeature::getLabel).toList());
        return m;
    }

    private Map<String, Object> priceView(String display, List<BillingPlanPrice> prices, String currency) {
        if ("FREE".equals(display)) return Map.of("currency", currency, "free", true);
        if (!"FIXED".equals(display) && !"STARTING_AT".equals(display)) return null;
        BillingPlanPrice annual = prices.stream()
                .filter(x -> currency.equals(x.getCurrency()) && "ANNUAL".equals(x.getBillingInterval())).findFirst().orElse(null);
        BillingPlanPrice monthly = prices.stream()
                .filter(x -> currency.equals(x.getCurrency()) && "MONTHLY".equals(x.getBillingInterval())).findFirst().orElse(null);
        if (annual == null && monthly == null) return null;     // nothing to show in this currency
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currency", currency);
        m.put("startingAt", "STARTING_AT".equals(display));
        m.put("annualMinor", annual == null ? null : annual.getUnitAmountMinor());
        m.put("monthlyMinor", monthly == null ? null : monthly.getUnitAmountMinor());
        // The headline number: per month when billed annually, else the monthly price.
        m.put("perMonthBilledAnnuallyMinor", annual == null ? null : Math.round(annual.getUnitAmountMinor() / 12.0));
        return m;
    }

    private Map<String, Object> comparison(List<BillingPlan> orgPlans, List<BillingFeature> features) {
        Map<String, Set<String>> keysByPlan = new LinkedHashMap<>();
        orgPlans.forEach(p -> keysByPlan.put(p.getCode(), keys(p)));

        // Limits first — generated from the plan columns, never typed twice.
        List<Map<String, Object>> limitRows = new ArrayList<>();
        limitRows.add(limitRow("Frameworks or audit programmes", orgPlans, p -> count(p.getIncludedFrameworks())));
        limitRows.add(limitRow("Vendors under management", orgPlans, p -> count(p.getIncludedVendors())));
        limitRows.add(limitRow("Admin & manager seats", orgPlans, p -> count(p.getIncludedUsers())));
        limitRows.add(limitRow("Contributors, auditors and vendors", orgPlans, p -> "Unlimited"));
        if (orgPlans.stream().anyMatch(p -> p.getIncludedEntities() != null)) {
            limitRows.add(limitRow("Legal entities", orgPlans, p -> p.getIncludedEntities() == null ? "1" : count(p.getIncludedEntities())));
        }
        limitRows.add(limitRow("Support", orgPlans, p -> supportLabel(p.getSupportLevel())));

        // Feature groups in the order their first row appears.
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (BillingFeature f : features) {
            Map<String, Object> cells = new LinkedHashMap<>();
            for (BillingPlan p : orgPlans) {
                boolean on = f.isIncludedInAll() || keysByPlan.get(p.getCode()).contains(f.getFeatureKey());
                cells.put(p.getCode(), on ? "INCLUDED" : f.isAddonAvailable() ? "ADDON" : null);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", f.getFeatureKey());
            row.put("label", f.getLabel());
            row.put("description", blankToNull(f.getDescription()));
            row.put("cells", cells);
            groups.computeIfAbsent(f.getGroupName(), k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> groupList = new ArrayList<>();
        groupList.add(Map.of("name", "Plan limits", "rows", limitRows));
        groups.forEach((name, rows) -> groupList.add(Map.of("name", name, "rows", rows)));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plans", orgPlans.stream().map(p -> Map.of("code", p.getCode(), "name", p.getName())).toList());
        m.put("groups", groupList);
        return m;
    }

    private interface Cell { String of(BillingPlan p); }

    private Map<String, Object> limitRow(String label, List<BillingPlan> plans, Cell cell) {
        Map<String, Object> cells = new LinkedHashMap<>();
        plans.forEach(p -> cells.put(p.getCode(), cell.of(p)));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", null);
        row.put("label", label);
        row.put("description", null);
        row.put("cells", cells);
        return row;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    public static String audienceOf(BillingPlan p) {
        return p.getAudience() == null || p.getAudience().isBlank() ? "ORGANISATION" : p.getAudience();
    }

    public static String displayOf(BillingPlan p) {
        return p.getPriceDisplay() == null || p.getPriceDisplay().isBlank() ? "CONTACT_SALES" : p.getPriceDisplay();
    }

    public static boolean isFree(BillingPlan p) { return "FREE".equals(displayOf(p)); }

    private static Set<String> keys(BillingPlan p) {
        if (p.getFeatureKeysCsv() == null) return Set.of();
        return Arrays.stream(p.getFeatureKeysCsv().split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private static List<String> lines(String s) {
        if (s == null) return List.of();
        return Arrays.stream(s.split("\n")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String count(Integer n) { return n == null ? "Unlimited" : String.format("%,d", n); }

    static String supportLabel(String level) {
        if (level == null) return "Email";
        return switch (level) {
            case "ONBOARDING" -> "Email + guided onboarding";
            case "NAMED_CONTACT_SLA" -> "Named contact with SLA";
            default -> "Email";
        };
    }

    static String defaultCtaLabel(String cta) {
        return switch (cta) {
            case "TRIAL" -> "Start free trial";
            case "CONTACT_SALES" -> "Talk to sales";
            case "APPLY" -> "Apply as a firm";
            default -> "Book a demo";
        };
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String blankToNull(String s) { return blank(s) ? null : s.trim(); }
}
