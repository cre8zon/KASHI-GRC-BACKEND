package com.kashi.grc.billing.service;

import com.kashi.grc.billing.domain.SubscriptionLine;
import com.kashi.grc.billing.domain.TenantMrrSnapshot;
import com.kashi.grc.billing.domain.TenantSubscription;
import com.kashi.grc.billing.repository.SubscriptionLineRepository;
import com.kashi.grc.billing.repository.TenantMrrSnapshotRepository;
import com.kashi.grc.billing.repository.TenantSubscriptionRepository;
import com.kashi.grc.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Writes the month-end snapshot. Append-only.
 *
 * Running it twice for the same month is safe: tenants already snapshotted for
 * that month are skipped, so a re-run only fills in tenants that were missing
 * (e.g. the job failed half way). Nothing ever overwrites an existing row —
 * that is the property that makes the retention numbers trustworthy.
 *
 * Which tenants: every tenant that has ever had a subscription row. A tenant
 * with no commercial history has nothing to say about revenue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MrrSnapshotService {

    private final TenantSubscriptionRepository subscriptionRepository;
    private final SubscriptionLineRepository   lineRepository;
    private final TenantMrrSnapshotRepository  snapshotRepository;
    private final SubscriptionService          subscriptionService;
    private final TenantUsageReader            usage;
    private final FxService                    fx;

    @Transactional
    public Map<String, Object> snapshot(YearMonth month) {
        if (!month.isBefore(YearMonth.now())) {
            throw new BusinessException("MONTH_NOT_CLOSED",
                    "Only a completed month can be snapshotted. " + month + " has not ended.");
        }
        String key = month.toString();
        LocalDate asOf = month.atEndOfMonth();

        Set<Long> commercialTenants = subscriptionRepository.findAll().stream()
                .filter(s -> !s.getTermStart().isAfter(asOf))
                .map(TenantSubscription::getTenantId).collect(Collectors.toSet());

        Set<Long> already = snapshotRepository.findByPeriodMonth(key).stream()
                .map(TenantMrrSnapshot::getTenantId).collect(Collectors.toSet());

        Map<Long, TenantSubscription> live = subscriptionService.liveByTenant(asOf);
        Map<Long, List<SubscriptionLine>> linesBySub = live.isEmpty() ? Map.of()
                : lineRepository.findBySubscriptionIdIn(live.values().stream().map(TenantSubscription::getId).toList())
                    .stream().collect(Collectors.groupingBy(SubscriptionLine::getSubscriptionId));

        Map<Long, Long> seatsActive = usage.seatsActiveSince(month.atDay(1).atStartOfDay());
        Map<Long, Long> vendors     = usage.vendorsTotal();
        Map<Long, Long> aiCost      = usage.aiCostMicros(key);

        List<TenantMrrSnapshot> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Long tenantId : commercialTenants) {
            if (already.contains(tenantId)) continue;
            TenantSubscription s = live.get(tenantId);
            long mrr = s == null ? 0L : subscriptionService.mrrMinor(s, linesBySub.getOrDefault(s.getId(), List.of()));
            String cur = s == null ? fx.reportingCurrency() : s.getCurrency();
            long reporting;
            if (fx.supports(cur)) {
                reporting = fx.toReporting(mrr, cur);
            } else {
                reporting = 0L;
                warnings.add("Tenant " + tenantId + ": no FX rate for " + cur + " — reporting MRR recorded as 0");
            }
            rows.add(TenantMrrSnapshot.builder()
                    .tenantId(tenantId)
                    .periodMonth(key)
                    .subscriptionId(s == null ? null : s.getId())
                    .planCode(s == null ? null : s.getPlanCode())
                    .status(s == null ? "NONE" : s.getStatus())
                    .mrrMinor(mrr)
                    .currency(cur)
                    .mrrReportingMinor(reporting)
                    .reportingCurrency(fx.reportingCurrency())
                    .seatsActive(seatsActive.getOrDefault(tenantId, 0L).intValue())
                    .seatsPurchased(s == null ? null : s.getSeatsPurchased())
                    .vendorsActive(vendors.getOrDefault(tenantId, 0L).intValue())
                    .vendorsPurchased(s == null ? null : s.getVendorsPurchased())
                    .aiCostMicros(aiCost.getOrDefault(tenantId, 0L))
                    .build());
        }
        snapshotRepository.saveAll(rows);
        log.info("[BILLING] Snapshot {} | written={} skipped={} warnings={}", key, rows.size(), already.size(), warnings.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodMonth", key);
        out.put("written", rows.size());
        out.put("alreadyPresent", already.size());
        out.put("warnings", warnings);
        return out;
    }
}
