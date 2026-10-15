package com.kashi.grc.billing.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.billing.domain.BillingInvoice;
import com.kashi.grc.billing.repository.BillingInvoiceRepository;
import com.kashi.grc.billing.service.InvoicePdfService;
import com.kashi.grc.billing.service.InvoiceService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.kashi.grc.common.dto.ApiResponse;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Invoices. Under /v1/admin/** — SIDE_SYSTEM only. */
@RestController
@RequestMapping("/v1/admin/billing")
@PreAuthorize(BillingAccess.VIEW)
@Tag(name = "Billing — Invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService           invoiceService;
    private final BillingInvoiceRepository invoiceRepository;
    private final TenantRepository         tenantRepository;
    private final UtilityService           utilityService;
    private final ObjectMapper             objectMapper;
    private final InvoicePdfService        invoicePdfService;

    @GetMapping("/invoices")
    @Transactional(readOnly = true)
    @Operation(summary = "Invoices, newest first. Optional ?tenantId= and ?status= (OVERDUE is computed)")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long tenantId,
                                                       @RequestParam(required = false) String status) {
        List<BillingInvoice> rows = tenantId != null
                ? invoiceRepository.findByTenantIdOrderByIdDesc(tenantId)
                : invoiceRepository.findAllByOrderByIdDesc();
        Map<Long, String> names = tenantRepository.findAll().stream()
                .collect(Collectors.toMap(Tenant::getId, Tenant::getName, (a, b) -> a));
        return ApiResponse.success(rows.stream()
                .filter(i -> status == null || status.isBlank() || status.equals(InvoiceService.effectiveStatus(i)))
                .map(i -> BillingViews.invoice(i, names.get(i.getTenantId())))
                .toList());
    }

    @GetMapping("/invoices/{id}/pdf")
    @Operation(summary = "The invoice as a PDF (server-rendered, with logo). DRAFT and VOID carry a watermark.")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id,
                                      @RequestParam(required = false, defaultValue = "false") boolean inline) {
        byte[] body = invoicePdfService.render(id);
        String name = invoicePdfService.fileName(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inline ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    @GetMapping("/invoices/{id}")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ApiResponse.success(full(invoiceService.require(id)));
    }

    @PostMapping("/subscriptions/{subscriptionId}/invoices")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Draft the invoice for one billing period of a subscription")
    public ApiResponse<Map<String, Object>> draftFromSubscription(@PathVariable Long subscriptionId,
                                                                  @RequestBody(required = false) Map<String, Object> body) {
        LocalDate start = body != null && body.get("periodStart") != null
                ? LocalDate.parse(body.get("periodStart").toString()) : null;
        return ApiResponse.success(full(invoiceService.draftFromSubscription(subscriptionId, start, actor())));
    }

    @PostMapping("/tenants/{tenantId}/invoices")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Draft a free-form invoice (services, one-off work)")
    public ApiResponse<Map<String, Object>> draftManual(@PathVariable Long tenantId, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(full(invoiceService.draftManual(tenantId,
                str(body.get("currency")), lines(body.get("lines")), str(body.get("notes")), actor())));
    }

    @PutMapping("/invoices/{id}")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Edit a draft: lines, tax rate (basis points), tax label, notes")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer bp = body.get("taxRateBp") == null ? null : ((Number) body.get("taxRateBp")).intValue();
        return ApiResponse.success(full(invoiceService.updateDraft(id,
                body.containsKey("lines") ? lines(body.get("lines")) : null,
                bp, str(body.get("taxLabel")), body.containsKey("notes") ? String.valueOf(body.get("notes")) : null)));
    }

    @DeleteMapping("/invoices/{id}")
    @PreAuthorize(BillingAccess.MANAGE)
    @Operation(summary = "Delete a draft (issued invoices are voided, never deleted)")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        invoiceService.deleteDraft(id);
        return ApiResponse.success();
    }

    @PostMapping("/invoices/{id}/issue")
    @PreAuthorize(BillingAccess.MANAGE)
    public ApiResponse<Map<String, Object>> issue(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        LocalDate d = body != null && body.get("issueDate") != null ? LocalDate.parse(body.get("issueDate").toString()) : null;
        return ApiResponse.success(full(invoiceService.issue(id, d)));
    }

    @PostMapping("/invoices/{id}/mark-paid")
    @PreAuthorize(BillingAccess.MANAGE)
    public ApiResponse<Map<String, Object>> markPaid(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        LocalDateTime at = body != null && body.get("paidAt") != null
                ? LocalDate.parse(body.get("paidAt").toString().substring(0, 10)).atStartOfDay() : null;
        return ApiResponse.success(full(invoiceService.markPaid(id, at, body == null ? null : str(body.get("reference")))));
    }

    @PostMapping("/invoices/{id}/void")
    @PreAuthorize(BillingAccess.MANAGE)
    public ApiResponse<Map<String, Object>> voidInvoice(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.success(full(invoiceService.voidInvoice(id, str(body.get("reason")))));
    }

    // ── internals ───────────────────────────────────────────────────────────

    private Map<String, Object> full(BillingInvoice i) {
        String name = tenantRepository.findById(i.getTenantId()).map(Tenant::getName).orElse(null);
        Map<String, Object> m = BillingViews.invoice(i, name);
        m.put("lines", invoiceService.lines(i.getId()).stream().map(BillingViews::invoiceLine).toList());
        return m;
    }

    private List<InvoiceService.LineInput> lines(Object raw) {
        if (raw == null) return List.of();
        return objectMapper.convertValue(raw, new TypeReference<List<InvoiceService.LineInput>>() {});
    }

    private static String str(Object o) { if (o == null) return null; String s = o.toString().trim(); return s.isEmpty() ? null : s; }

    private Long actor() { return utilityService.getLoggedInDataContext().getId(); }
}
