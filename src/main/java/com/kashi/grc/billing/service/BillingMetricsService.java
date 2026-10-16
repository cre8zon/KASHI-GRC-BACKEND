package com.kashi.grc.billing.service;

import com.kashi.grc.billing.domain.AcquisitionCost;
import com.kashi.grc.billing.domain.SubscriptionLine;
import com.kashi.grc.billing.domain.TenantMrrSnapshot;
import com.kashi.grc.billing.domain.TenantSubscription;
import com.kashi.grc.billing.repository.AcquisitionCostRepository;
import com.kashi.grc.billing.repository.SubscriptionLineRepository;
import com.kashi.grc.billing.repository.TenantMrrSnapshotRepository;
import com.kashi.grc.billing.repository.TenantSubscriptionRepository;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every B2B SaaS number on the Billing Overview, computed in one place.
 *
 * ── TWO SOURCES, NEVER MIXED IN ONE NUMBER ────────────────────────────────
 *   "Now" figures (MRR, ARR, customers, renewals, utilisation) read the live
 *   contracts table — current truth.
 *   Period figures (NRR, GRR, movements, churn, quick ratio, trend) read
 *   tenant_mrr_snapshots only — immutable history. A month without a snapshot
 *   is reported as missing, never back-filled from contracts.
 *
 * ── DEFINITIONS, WRITTEN DOWN ONCE ────────────────────────────────────────
 *   NRR (12m)  fixed cohort = tenants with MRR > 0 at M-12. Sum their MRR at M
 *              ÷ sum at M-12. Customers acquired since never enter it.
 *   GRR (12m)  same cohort, each tenant capped at its M-12 MRR. ≤ 100%.
 *   Movements  per tenant, snapshot M-1 vs M:
 *                new           0 → >0, never paid before
 *                reactivation  0 → >0, paid at some earlier month
 *                expansion     up
 *                contraction   down, still > 0
 *                churned       >0 → 0
 *   Quick ratio (new + expansion + reactivation) ÷ (churned + contraction)
 *   Logo churn  tenants going >0 → 0 in the month ÷ paying tenants at M-1
 *   CAC         fully loaded spend in the window ÷ tenants whose first_paid_at
 *               falls in the window
 *
 * All money is in the reporting currency, minor units.
 */
@Service
@RequiredArgsConstructor
public class BillingMetricsService {

    private final TenantSubscriptionRepository subscriptionRepository;
    private final SubscriptionLineRepository   lineRepository;
    private final TenantMrrSnapshotRepository  snapshotRepository;
    private final AcquisitionCostRepository    acquisitionCostRepository;
    private final TenantRepository             tenantRepository;
    private final SubscriptionService          subscriptionService;
    private final TenantUsageReader            usage;
    private final FxService                    fx;

    /** Currency of AiUsageCounter.cost_micros — whatever the provider rates in AiProperties are quoted in. */
    @org.springframework.beans.factory.annotation.Value("${kashi.billing.ai-cost-currency:USD}")
    private String aiCostCurrency;

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        LocalDate today = LocalDate.now();
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> dataWarnings = new ArrayList<>();
        out.put("reportingCurrency", fx.reportingCurrency());
        out.put("asOf", today.toString());

        Map<Long, Tenant> tenants = tenantRepository.findAll().stream()
                .collect(Collectors.toMap(Tenant::getId, Function.identity()));

        // ── NOW ─────────────────────────────────────────────────────────────
        Map<Long, TenantSubscription> live = subscriptionService.liveByTenant(today);
        Map<Long, List<SubscriptionLine>> lines = live.isEmpty() ? Map.of()
                : lineRepository.findBySubscriptionIdIn(live.values().stream().map(TenantSubscription::getId).toList())
                    .stream().collect(Collectors.groupingBy(SubscriptionLine::getSubscriptionId));

        Map<Long, Long> mrrNow = new HashMap<>();
        int trials = 0;
        for (TenantSubscription s : live.values()) {
            if ("TRIAL".equals(s.getStatus())) { trials++; continue; }
            long m = subscriptionService.mrrMinor(s, lines.getOrDefault(s.getId(), List.of()));
            if (!fx.supports(s.getCurrency())) {
                dataWarnings.add("No FX rate for " + s.getCurrency() + " (tenant " + s.getTenantId() + ") — excluded from totals");
                continue;
            }
            if (m > 0) mrrNow.put(s.getTenantId(), fx.toReporting(m, s.getCurrency()));
        }
        long mrr = mrrNow.values().stream().mapToLong(Long::longValue).sum();
        int paying = mrrNow.size();

        Map<String, Object> now = new LinkedHashMap<>();
        now.put("mrrMinor", mrr);
        now.put("arrMinor", mrr * 12);
        now.put("payingCustomers", paying);
        now.put("trials", trials);
        now.put("arpaMinor", paying == 0 ? null : mrr / paying);
        now.put("pastDue", live.values().stream().filter(s -> "PAST_DUE".equals(s.getStatus())).count());
        now.put("cancelling", live.values().stream().filter(s -> "CANCELLED".equals(s.getStatus())).count());

        // ARR concentration — share of ARR from the top five customers.
        List<Long> sorted = mrrNow.values().stream().sorted(Comparator.reverseOrder()).toList();
        long top5 = sorted.stream().limit(5).mapToLong(Long::longValue).sum();
        now.put("top5ConcentrationPct", Money.pct(top5, mrr));

        // Sales motion split.
        Map<String, Long> byMotion = new TreeMap<>();
        for (var e : mrrNow.entrySet()) {
            String motion = Optional.ofNullable(live.get(e.getKey()).getSalesMotion()).orElse("UNSPECIFIED");
            byMotion.merge(motion, e.getValue(), Long::sum);
        }
        now.put("mrrBySalesMotion", byMotion);

        // Plan mix.
        Map<String, Long> byPlan = new TreeMap<>();
        for (var e : mrrNow.entrySet()) byPlan.merge(live.get(e.getKey()).getPlanCode(), e.getValue(), Long::sum);
        now.put("mrrByPlan", byPlan);
        out.put("now", now);

        // ── HISTORY (snapshots) ─────────────────────────────────────────────
        List<TenantMrrSnapshot> all = snapshotRepository.findAll();
        Map<String, Map<Long, Long>> byMonth = new TreeMap<>();
        for (TenantMrrSnapshot s : all) {
            byMonth.computeIfAbsent(s.getPeriodMonth(), k -> new HashMap<>())
                   .put(s.getTenantId(), s.getMrrReportingMinor());
        }
        YearMonth lastClosed = YearMonth.now().minusMonths(1);
        String m0 = lastClosed.toString();
        String mPrev = lastClosed.minusMonths(1).toString();
        String m12 = lastClosed.minusMonths(12).toString();

        if (byMonth.isEmpty()) {
            dataWarnings.add("No monthly snapshots yet. Period metrics start after the first month-end snapshot runs.");
        } else if (!byMonth.containsKey(m0)) {
            dataWarnings.add("Snapshot for " + m0 + " is missing — run it from Snapshots.");
        }

        out.put("trend", trend(byMonth, lastClosed, 12));
        out.put("movements", movements(byMonth, mPrev, m0));
        out.put("retention", retention(byMonth, m12, m0));
        out.put("trailingLogoChurn", trailingLogoChurn(byMonth, lastClosed));

        // ── RENEWALS (next 90 days) ─────────────────────────────────────────
        List<Map<String, Object>> renewals = new ArrayList<>();
        for (TenantSubscription s : subscriptionRepository.findRenewingBetween(today, today.plusDays(90))) {
            if (subscriptionRepository.findFirstByPreviousSubscriptionId(s.getId()).isPresent()) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("subscriptionId", s.getId());
            r.put("tenantId", s.getTenantId());
            r.put("tenantName", Optional.ofNullable(tenants.get(s.getTenantId())).map(Tenant::getName).orElse("#" + s.getTenantId()));
            r.put("planCode", s.getPlanCode());
            r.put("termEnd", s.getTermEnd().toString());
            r.put("autoRenew", s.isAutoRenew());
            r.put("status", s.getStatus());
            r.put("mrrMinor", mrrNow.getOrDefault(s.getTenantId(), 0L));
            renewals.add(r);
        }
        out.put("renewals", renewals);

        // ── UTILISATION (leading indicators both ways) ──────────────────────
        Map<Long, Long> seatsActive = usage.seatsActiveSince(today.minusDays(30).atStartOfDay());
        Map<Long, Long> seatsTotal  = usage.seatsTotal();
        Map<Long, Long> vendors     = usage.vendorsTotal();
        Map<Long, Long> aiCost      = usage.aiCostMicros(YearMonth.now().toString());
        List<Map<String, Object>> util = new ArrayList<>();
        for (TenantSubscription s : live.values()) {
            Long tid = s.getTenantId();
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("tenantId", tid);
            u.put("tenantName", Optional.ofNullable(tenants.get(tid)).map(Tenant::getName).orElse("#" + tid));
            u.put("planCode", s.getPlanCode());
            u.put("status", s.getStatus());
            u.put("mrrMinor", mrrNow.getOrDefault(tid, 0L));
            u.put("seatsActive30d", seatsActive.getOrDefault(tid, 0L));
            u.put("seatsTotal", seatsTotal.getOrDefault(tid, 0L));
            u.put("seatsPurchased", s.getSeatsPurchased());
            u.put("vendors", vendors.getOrDefault(tid, 0L));
            u.put("vendorsPurchased", s.getVendorsPurchased());
            Double vendorUtil = s.getVendorsPurchased() == null || s.getVendorsPurchased() == 0 ? null
                    : Money.pct(vendors.getOrDefault(tid, 0L), s.getVendorsPurchased());
            u.put("vendorUtilisationPct", vendorUtil);
            Double seatActivity = Money.pct(seatsActive.getOrDefault(tid, 0L), seatsTotal.getOrDefault(tid, 0L));
            u.put("seatActivityPct", seatActivity);
            // AI cost this month as a share of MRR — the cost-to-serve line that
            // tells you a heavy AI tenant on a flat plan is unprofitable.
            long aiMinor = aiCostReporting(aiCost.getOrDefault(tid, 0L));
            u.put("aiCostMinor", aiMinor);
            u.put("aiCostPctOfMrr", Money.pct(aiMinor, mrrNow.getOrDefault(tid, 0L)));
            String signal = null;
            if (vendorUtil != null && vendorUtil >= 90) signal = "EXPANSION";
            else if ((vendorUtil != null && vendorUtil < 40) || (seatActivity != null && seatActivity < 40)) signal = "CHURN_RISK";
            if (seatsTotal.getOrDefault(tid, 0L) <= 1) signal = "CHURN_RISK";   // one user = one champion, no account
            u.put("signal", signal);
            util.add(u);
        }
        util.sort(Comparator.comparing((Map<String, Object> m) -> (Long) m.get("mrrMinor")).reversed());
        out.put("utilisation", util);

        // ── CAC (trailing 3 closed months) ──────────────────────────────────
        out.put("cac", cac(lastClosed, 3, tenants.values()));

        // ── DATA QUALITY ────────────────────────────────────────────────────
        long noChannel = live.keySet().stream().map(tenants::get).filter(Objects::nonNull)
                .filter(t -> t.getAcquisitionChannel() == null).count();
        if (noChannel > 0) dataWarnings.add(noChannel + " live customer(s) have no acquisition channel — CAC by channel will be incomplete.");
        out.put("warnings", dataWarnings);
        return out;
    }

    // ══════════════════════ PERIOD CALCULATIONS ══════════════════════════════

    private List<Map<String, Object>> trend(Map<String, Map<Long, Long>> byMonth, YearMonth last, int months) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) {
            String k = last.minusMonths(i).toString();
            Map<Long, Long> m = byMonth.get(k);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("month", k);
            p.put("hasSnapshot", m != null);
            p.put("mrrMinor", m == null ? null : m.values().stream().mapToLong(Long::longValue).sum());
            p.put("payingCustomers", m == null ? null : m.values().stream().filter(v -> v > 0).count());
            out.add(p);
        }
        return out;
    }

    public Map<String, Object> movements(Map<String, Map<Long, Long>> byMonth, String prev, String cur) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", prev);
        out.put("to", cur);
        Map<Long, Long> a = byMonth.get(prev), b = byMonth.get(cur);
        if (a == null || b == null) {
            out.put("available", false);
            out.put("reason", "Needs snapshots for both " + prev + " and " + cur);
            return out;
        }
        Set<Long> paidBefore = new HashSet<>();
        byMonth.forEach((k, m) -> { if (k.compareTo(prev) < 0) m.forEach((t, v) -> { if (v > 0) paidBefore.add(t); }); });

        long nw = 0, react = 0, exp = 0, contr = 0, churn = 0;
        int nwN = 0, reactN = 0, churnN = 0;
        Set<Long> ids = new HashSet<>(a.keySet()); ids.addAll(b.keySet());
        for (Long t : ids) {
            long x = a.getOrDefault(t, 0L), y = b.getOrDefault(t, 0L);
            if (x == 0 && y > 0) {
                if (paidBefore.contains(t)) { react += y; reactN++; } else { nw += y; nwN++; }
            } else if (x > 0 && y == 0) { churn += x; churnN++; }
            else if (y > x) exp += y - x;
            else if (y < x) contr += x - y;
        }
        long startPaying = a.values().stream().filter(v -> v > 0).count();
        out.put("available", true);
        out.put("newMinor", nw);           out.put("newCount", nwN);
        out.put("reactivationMinor", react); out.put("reactivationCount", reactN);
        out.put("expansionMinor", exp);
        out.put("contractionMinor", contr);
        out.put("churnedMinor", churn);    out.put("churnedCount", churnN);
        out.put("netNewMinor", nw + react + exp - contr - churn);
        out.put("quickRatio", Money.ratio(nw + react + exp, churn + contr));
        out.put("logoChurnPct", Money.pct(churnN, startPaying));
        long startMrr = a.values().stream().mapToLong(Long::longValue).sum();
        out.put("revenueChurnPct", Money.pct(churn + contr, startMrr));
        out.put("expansionShareOfNetNewPct", Money.pct(exp, nw + react + exp));
        return out;
    }

    private Map<String, Object> retention(Map<String, Map<Long, Long>> byMonth, String base, String cur) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cohortMonth", base);
        out.put("measuredMonth", cur);
        Map<Long, Long> then = byMonth.get(base), now = byMonth.get(cur);
        if (then == null || now == null) {
            out.put("available", false);
            out.put("reason", "NRR and GRR need 13 consecutive months of snapshots (" + base + " and " + cur + ").");
            return out;
        }
        long sumThen = 0, sumNow = 0, sumCapped = 0;
        int cohort = 0, retained = 0;
        for (var e : then.entrySet()) {
            if (e.getValue() <= 0) continue;
            cohort++;
            long n = now.getOrDefault(e.getKey(), 0L);
            sumThen += e.getValue();
            sumNow += n;
            sumCapped += Math.min(n, e.getValue());
            if (n > 0) retained++;
        }
        out.put("available", cohort > 0);
        out.put("cohortSize", cohort);
        out.put("nrrPct", Money.pct(sumNow, sumThen));
        out.put("grrPct", Money.pct(sumCapped, sumThen));
        out.put("logoRetentionPct", Money.pct(retained, cohort));
        return out;
    }

    private Map<String, Object> trailingLogoChurn(Map<String, Map<Long, Long>> byMonth, YearMonth last) {
        int churned = 0, startPaying = 0, monthsUsed = 0;
        for (int i = 0; i < 12; i++) {
            Map<Long, Long> b = byMonth.get(last.minusMonths(i).toString());
            Map<Long, Long> a = byMonth.get(last.minusMonths(i + 1).toString());
            if (a == null || b == null) continue;
            monthsUsed++;
            for (var e : a.entrySet()) {
                if (e.getValue() <= 0) continue;
                startPaying++;
                if (b.getOrDefault(e.getKey(), 0L) == 0) churned++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monthsUsed", monthsUsed);
        out.put("churnedLogos", churned);
        // Average monthly logo churn over the months available.
        out.put("avgMonthlyLogoChurnPct", Money.pct(churned, startPaying));
        return out;
    }

    private Map<String, Object> cac(YearMonth last, int window, Collection<Tenant> tenants) {
        YearMonth from = last.minusMonths(window - 1);
        List<AcquisitionCost> costs = acquisitionCostRepository.findByPeriodMonthBetween(from.toString(), last.toString());
        long spend = 0;
        Map<String, Long> spendByChannel = new TreeMap<>();
        for (AcquisitionCost c : costs) {
            if (!fx.supports(c.getCurrency())) continue;
            long v = fx.toReporting(c.getSpendMinor(), c.getCurrency());
            spend += v;
            spendByChannel.merge(c.getChannel(), v, Long::sum);
        }
        LocalDate start = from.atDay(1), end = last.atEndOfMonth();
        List<Tenant> won = tenants.stream().filter(t -> t.getFirstPaidAt() != null
                && !t.getFirstPaidAt().isBefore(start) && !t.getFirstPaidAt().isAfter(end)).toList();
        Map<String, Long> wonByChannel = won.stream().collect(Collectors.groupingBy(
                t -> Optional.ofNullable(t.getAcquisitionChannel()).orElse("UNSPECIFIED"), TreeMap::new, Collectors.counting()));

        // New MRR from the customers won, read from their current live row.
        long newMrr = 0;
        Map<Long, TenantSubscription> live = subscriptionService.liveByTenant(LocalDate.now());
        for (Tenant t : won) {
            TenantSubscription s = live.get(t.getId());
            if (s != null && fx.supports(s.getCurrency())) newMrr += fx.toReporting(subscriptionService.mrrMinor(s), s.getCurrency());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", from + " → " + last);
        out.put("spendMinor", spend);
        out.put("customersWon", won.size());
        Long cacMinor = won.isEmpty() ? null : spend / won.size();
        out.put("cacMinor", cacMinor);
        Long avgNewMrr = won.isEmpty() ? null : newMrr / won.size();
        out.put("avgNewMrrMinor", avgNewMrr);
        // Payback on revenue, not gross margin — the honest version needs cost-to-serve
        // beyond AI, which nothing records yet. Read it as a floor.
        out.put("paybackMonths", (cacMinor == null || avgNewMrr == null || avgNewMrr == 0) ? null
                : Math.round(cacMinor * 10.0 / avgNewMrr) / 10.0);
        out.put("spendByChannel", spendByChannel);
        out.put("wonByChannel", wonByChannel);
        out.put("note", won.size() < 10
                ? "Fewer than ten customers won in the window — treat CAC as noise with decimal places."
                : null);
        return out;
    }

    /** cost_micros → minor units (÷ 10,000) → reporting currency. */
    private long aiCostReporting(long micros) {
        if (micros == 0 || !fx.supports(aiCostCurrency)) return 0L;
        long minor = Math.round(micros / 10_000.0);
        return fx.toReporting(minor, aiCostCurrency);
    }
}
