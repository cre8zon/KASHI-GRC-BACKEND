package com.kashi.grc.billing.controller;

import com.kashi.grc.billing.domain.AcquisitionCost;
import com.kashi.grc.billing.domain.TenantMrrSnapshot;
import com.kashi.grc.billing.repository.AcquisitionCostRepository;
import com.kashi.grc.billing.repository.TenantMrrSnapshotRepository;
import com.kashi.grc.billing.service.BillingMetricsService;
import com.kashi.grc.billing.service.MrrSnapshotService;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import com.kashi.grc.billing.service.BillingAccess;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/** Metrics, snapshots and acquisition spend. Under /v1/admin/** — SIDE_SYSTEM only. */
@RestController
@RequestMapping("/v1/admin/billing")
@PreAuthorize(BillingAccess.VIEW)
@Tag(name = "Billing — Metrics")
@RequiredArgsConstructor
public class BillingMetricsController {

    private final BillingMetricsService       metricsService;
    private final MrrSnapshotService          snapshotService;
    private final TenantMrrSnapshotRepository snapshotRepository;
    private final AcquisitionCostRepository   costRepository;
    private final TenantRepository            tenantRepository;
    private final UtilityService              utilityService;

    @GetMapping("/metrics/overview")
    @Operation(summary = "MRR/ARR, NRR/GRR, movements, quick ratio, churn, renewals, utilisation, CAC")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(metricsService.overview());
    }

    // ── Snapshots ───────────────────────────────────────────────────────────

    @GetMapping("/snapshots")
    @Transactional(readOnly = true)
    @Operation(summary = "Snapshot rows for one month (?month=YYYY-MM), or the list of months that have one")
    public ApiResponse<Object> snapshots(@RequestParam(required = false) String month) {
        if (month == null || month.isBlank()) {
            TreeMap<String, Long> counts = snapshotRepository.findAll().stream()
                    .collect(Collectors.groupingBy(TenantMrrSnapshot::getPeriodMonth, TreeMap::new, Collectors.counting()));
            List<Map<String, Object>> out = new ArrayList<>();
            counts.descendingMap().forEach((k, v) -> out.add(Map.of("periodMonth", k, "tenants", v)));
            return ApiResponse.success(out);
        }
        Map<Long, String> names = tenantRepository.findAll().stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName, (a, b) -> a));
        return ApiResponse.success(snapshotRepository.findByPeriodMonth(parse(month).toString()).stream()
                .map(s -> BillingViews.snapshot(s, names.get(s.getTenantId()))).toList());
    }

    @PostMapping("/snapshots/run")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Write the snapshot for a closed month. Fills missing tenants only; never overwrites.")
    public ApiResponse<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        Object m = body.get("month");
        YearMonth month = m == null ? YearMonth.now().minusMonths(1) : parse(m.toString());
        return ApiResponse.success(snapshotService.snapshot(month));
    }

    // ── Acquisition costs ──────────────────────────────────────────────────

    @GetMapping("/acquisition-costs")
    public ApiResponse<List<Map<String, Object>>> costs() {
        return ApiResponse.success(costRepository.findAllByOrderByPeriodMonthDescIdDesc().stream()
                .map(BillingViews::cost).toList());
    }

    @PostMapping("/acquisition-costs")
    @PreAuthorize(BillingAccess.MANAGE)
    public ApiResponse<Map<String, Object>> addCost(@RequestBody Map<String, Object> body) {
        AcquisitionCost c = AcquisitionCost.builder().createdBy(utilityService.getLoggedInDataContext().getId()).build();
        apply(c, body);
        return ApiResponse.success(BillingViews.cost(costRepository.save(c)));
    }

    @PutMapping("/acquisition-costs/{id}")
    @PreAuthorize(BillingAccess.MANAGE)
    public ApiResponse<Map<String, Object>> updateCost(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        AcquisitionCost c = costRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("AcquisitionCost", id));
        apply(c, body);
        return ApiResponse.success(BillingViews.cost(costRepository.save(c)));
    }

    @DeleteMapping("/acquisition-costs/{id}")
    @PreAuthorize(BillingAccess.MANAGE)
    public ApiResponse<Void> deleteCost(@PathVariable Long id) {
        costRepository.deleteById(id);
        return ApiResponse.success();
    }

    private void apply(AcquisitionCost c, Map<String, Object> b) {
        if (b.get("periodMonth") != null) c.setPeriodMonth(parse(b.get("periodMonth").toString()).toString());
        if (b.get("channel") != null)     c.setChannel(b.get("channel").toString());
        if (b.containsKey("costType"))    c.setCostType(b.get("costType") == null ? null : b.get("costType").toString());
        if (b.get("spendMinor") != null)  c.setSpendMinor(((Number) b.get("spendMinor")).longValue());
        if (b.get("currency") != null)    c.setCurrency(b.get("currency").toString().toUpperCase());
        if (b.containsKey("notes"))       c.setNotes(b.get("notes") == null ? null : b.get("notes").toString());
        if (c.getPeriodMonth() == null || c.getChannel() == null || c.getSpendMinor() == null) {
            throw new BusinessException("FIELD_REQUIRED", "periodMonth, channel and spendMinor are required.");
        }
        if (c.getSpendMinor() < 0) throw new BusinessException("BAD_AMOUNT", "Spend cannot be negative.");
    }

    private static YearMonth parse(String s) {
        try { return YearMonth.parse(s.trim().substring(0, 7)); }
        catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            throw new BusinessException("BAD_MONTH", "Month must be YYYY-MM.");
        }
    }
}
