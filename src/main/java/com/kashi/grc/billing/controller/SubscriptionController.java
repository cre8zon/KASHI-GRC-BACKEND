package com.kashi.grc.billing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.billing.domain.BillingPlan;
import com.kashi.grc.billing.domain.SubscriptionLine;
import com.kashi.grc.billing.domain.TenantSubscription;
import com.kashi.grc.billing.repository.BillingInvoiceRepository;
import com.kashi.grc.billing.repository.BillingPlanRepository;
import com.kashi.grc.billing.repository.SubscriptionLineRepository;
import com.kashi.grc.billing.repository.TenantSubscriptionRepository;
import com.kashi.grc.billing.service.SubscriptionService;
import com.kashi.grc.billing.service.SubscriptionService.SubscriptionInput;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
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

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Subscriptions. Under /v1/admin/** — SIDE_SYSTEM only, enforced by SecurityConfig.
 *
 * Verbs, not a generic PUT: create, renew, change-plan, cancel. Each writes a
 * chained row (see SubscriptionService). PATCH only touches administrative
 * fields that do not change what the contract is.
 */
@RestController
@RequestMapping("/v1/admin/billing")
@PreAuthorize(BillingAccess.VIEW)
@Tag(name = "Billing — Subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService          subscriptionService;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final SubscriptionLineRepository   lineRepository;
    private final BillingInvoiceRepository     invoiceRepository;
    private final BillingPlanRepository        planRepository;
    private final TenantRepository             tenantRepository;
    private final UtilityService               utilityService;
    private final ObjectMapper                 objectMapper;

    @GetMapping("/subscriptions")
    @Transactional(readOnly = true)
    @Operation(summary = "All subscriptions, newest first. ?status=ACTIVE,TRIAL &current=true for heads of chain only")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                                       @RequestParam(required = false, defaultValue = "false") boolean current) {
        Set<String> statuses = status == null || status.isBlank() ? null
                : Arrays.stream(status.split(",")).map(String::trim).collect(Collectors.toSet());
        Map<Long, String> names = tenantNames();
        List<TenantSubscription> rows = current
                ? new ArrayList<>(subscriptionService.liveByTenant(LocalDate.now()).values())
                : subscriptionRepository.findAll();
        Map<Long, List<SubscriptionLine>> lines = linesFor(rows);
        return ApiResponse.success(rows.stream()
                .filter(s -> statuses == null || statuses.contains(s.getStatus()))
                .sorted(Comparator.comparing(TenantSubscription::getTermStart).reversed()
                        .thenComparing(TenantSubscription::getId, Comparator.reverseOrder()))
                .map(s -> BillingViews.subscription(s, names.get(s.getTenantId()),
                        subscriptionService.mrrMinor(s, lines.getOrDefault(s.getId(), List.of()))))
                .toList());
    }

    @GetMapping("/tenants/{tenantId}/subscriptions")
    @Transactional(readOnly = true)
    @Operation(summary = "A tenant's subscription history (the chain) and which row is in force today")
    public ApiResponse<Map<String, Object>> forTenant(@PathVariable Long tenantId) {
        List<TenantSubscription> rows = subscriptionRepository.findByTenantIdOrderByTermStartDescIdDesc(tenantId);
        Map<Long, List<SubscriptionLine>> lines = linesFor(rows);
        Optional<TenantSubscription> cur = subscriptionService.current(tenantId);
        String name = tenantRepository.findById(tenantId).map(Tenant::getName).orElse(null);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currentId", cur.map(TenantSubscription::getId).orElse(null));
        m.put("history", rows.stream().map(s -> {
            Map<String, Object> v = BillingViews.subscription(s, name,
                    subscriptionService.mrrMinor(s, lines.getOrDefault(s.getId(), List.of())));
            v.put("lines", lines.getOrDefault(s.getId(), List.of()).stream().map(BillingViews::line).toList());
            v.put("hasSuccessor", subscriptionRepository.findFirstByPreviousSubscriptionId(s.getId()).isPresent());
            return v;
        }).toList());
        return ApiResponse.success(m);
    }

    @PostMapping("/tenants/{tenantId}/subscriptions")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Start a tenant's first subscription (or a reactivation after churn)")
    public ApiResponse<Map<String, Object>> create(@PathVariable Long tenantId, @RequestBody Map<String, Object> body) {
        TenantSubscription s = subscriptionService.create(tenantId, input(body), actor());
        return ApiResponse.success(view(s));
    }

    @GetMapping("/subscriptions/{id}")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        TenantSubscription s = subscriptionService.require(id);
        Map<String, Object> v = view(s);
        v.put("lines", lineRepository.findBySubscriptionIdOrderByIdAsc(id).stream().map(BillingViews::line).toList());
        v.put("invoiceCount", invoiceRepository.findBySubscriptionIdOrderByIdDesc(id).size());
        return ApiResponse.success(v);
    }

    @PostMapping("/subscriptions/{id}/renew")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Book the next term (starts when this one ends unless termStart is given)")
    public ApiResponse<Map<String, Object>> renew(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.success(view(subscriptionService.renew(id, input(body), actor())));
    }

    @PostMapping("/subscriptions/{id}/change-plan")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Upgrade / downgrade from an effective date (termStart)")
    public ApiResponse<Map<String, Object>> changePlan(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(view(subscriptionService.changePlan(id, input(body), actor())));
    }

    @PostMapping("/subscriptions/{id}/cancel")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Cancel — runs to term end unless immediate=true")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String reason = body.get("reason") == null ? null : body.get("reason").toString();
        String note = body.get("note") == null ? null : body.get("note").toString();
        boolean immediate = Boolean.TRUE.equals(body.get("immediate"));
        return ApiResponse.success(view(subscriptionService.cancel(id, reason, note, immediate, actor())));
    }

    @PatchMapping("/subscriptions/{id}")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Administrative fields only: notes, poReference, autoRenew, salesMotion, ACTIVE↔PAST_DUE")
    public ApiResponse<Map<String, Object>> patch(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(view(subscriptionService.updateAdministrative(id, body)));
    }

    @PostMapping("/subscriptions/{id}/apply-entitlements")
    @PreAuthorize(BillingAccess.MANAGE)
    @Transactional
    @Operation(summary = "Re-apply the plan's LICENSED features to the tenant. mode = ADD_ONLY | EXACT")
    public ApiResponse<Map<String, List<String>>> applyEntitlements(@PathVariable Long id,
                                                                   @RequestBody(required = false) Map<String, Object> body) {
        TenantSubscription s = subscriptionService.require(id);
        BillingPlan plan = planRepository.findByCode(s.getPlanCode())
                .orElseThrow(() -> new BusinessException("UNKNOWN_PLAN", "No plan " + s.getPlanCode()));
        String mode = body != null && body.get("mode") != null ? body.get("mode").toString() : "ADD_ONLY";
        return ApiResponse.success(subscriptionService.applyEntitlements(s.getTenantId(), plan, mode, actor()));
    }

    @PostMapping("/maintenance/sweep")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Run the daily lapsed-term sweep now")
    public ApiResponse<Map<String, Integer>> sweep() {
        return ApiResponse.success(subscriptionService.sweepLapsed(LocalDate.now()));
    }

    // ── internals ───────────────────────────────────────────────────────────

    private SubscriptionInput input(Map<String, Object> body) {
        if (body == null) return new SubscriptionInput();
        // Jackson handles LocalDate (yyyy-MM-dd) and nested lines; unknown keys are an error worth seeing.
        return objectMapper.convertValue(body, SubscriptionInput.class);
    }

    private Map<String, Object> view(TenantSubscription s) {
        String name = tenantRepository.findById(s.getTenantId()).map(Tenant::getName).orElse(null);
        return BillingViews.subscription(s, name, subscriptionService.mrrMinor(s));
    }

    private Map<Long, List<SubscriptionLine>> linesFor(Collection<TenantSubscription> rows) {
        if (rows.isEmpty()) return Map.of();
        return lineRepository.findBySubscriptionIdIn(rows.stream().map(TenantSubscription::getId).toList())
                .stream().collect(Collectors.groupingBy(SubscriptionLine::getSubscriptionId));
    }

    private Map<Long, String> tenantNames() {
        return tenantRepository.findAll().stream().collect(Collectors.toMap(Tenant::getId, Tenant::getName, (a, b) -> a));
    }

    private Long actor() {
        return utilityService.getLoggedInDataContext().getId();
    }
}
