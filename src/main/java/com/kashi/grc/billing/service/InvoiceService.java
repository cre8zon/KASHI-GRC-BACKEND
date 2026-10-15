package com.kashi.grc.billing.service;

import com.kashi.grc.billing.domain.BillingInvoice;
import com.kashi.grc.billing.domain.BillingInvoiceLine;
import com.kashi.grc.billing.domain.SubscriptionLine;
import com.kashi.grc.billing.domain.TenantSubscription;
import com.kashi.grc.billing.repository.BillingInvoiceLineRepository;
import com.kashi.grc.billing.repository.BillingInvoiceRepository;
import com.kashi.grc.billing.repository.SubscriptionLineRepository;
import com.kashi.grc.billing.repository.TenantSubscriptionRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Invoice ledger: draft → issue → paid | void.
 *
 * ── NUMBERING ─────────────────────────────────────────────────────────────
 * Numbers are assigned at ISSUE, sequential within the Indian financial year
 * (April–March): {prefix}/{FY}/{0001}. Drafts carry no number, so deleting or
 * voiding a draft never leaves a gap — Indian GST rules expect a consecutive
 * series. A voided ISSUED invoice keeps its number (it was sent).
 *
 * ── TAX ───────────────────────────────────────────────────────────────────
 * Default treatment by the tenant's country: IN → 18% GST; anywhere else →
 * export of services, zero-rated under LUT. Both are overridable per invoice
 * while it is a draft. This is a starting point for your CA to confirm, not
 * tax advice — in particular CGST+SGST vs IGST depends on the buyer's state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final BillingInvoiceRepository     invoiceRepository;
    private final BillingInvoiceLineRepository invoiceLineRepository;
    private final TenantSubscriptionRepository subscriptionRepository;
    private final SubscriptionLineRepository   subscriptionLineRepository;
    private final TenantRepository             tenantRepository;

    @Value("${kashi.billing.invoice-prefix:KG}")
    private String invoicePrefix;

    @Value("${kashi.billing.payment-terms-days:30}")
    private int paymentTermsDays;

    @Value("${kashi.billing.default-sac:}")
    private String defaultSac;

    public BillingInvoice require(Long id) {
        return invoiceRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Invoice", id));
    }

    public List<BillingInvoiceLine> lines(Long invoiceId) {
        return invoiceLineRepository.findByInvoiceIdOrderByIdAsc(invoiceId);
    }

    /** ISSUED and past due — computed, never stored, so it cannot go stale. */
    public static String effectiveStatus(BillingInvoice i) {
        if ("ISSUED".equals(i.getStatus()) && i.getDueDate() != null && i.getDueDate().isBefore(LocalDate.now())) {
            return "OVERDUE";
        }
        return i.getStatus();
    }

    // ══════════════════════ DRAFT FROM SUBSCRIPTION ══════════════════════════

    /**
     * Drafts the invoice for one billing period of a subscription.
     *
     * The period length follows billingInterval (MONTHLY 1, QUARTERLY 3,
     * ANNUAL 12 months; MULTI_YEAR bills the whole term). Recurring lines are
     * pro-rated to the period; one-off lines (onboarding) are billed only on
     * the first period of the term.
     */
    @Transactional
    public BillingInvoice draftFromSubscription(Long subscriptionId, LocalDate periodStart, Long actorId) {
        TenantSubscription s = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", subscriptionId));
        if ("TRIAL".equals(s.getStatus())) {
            throw new BusinessException("TRIAL_NOT_BILLABLE", "A trial has nothing to invoice.");
        }
        LocalDate start = periodStart != null ? periodStart : s.getTermStart();
        if (start.isBefore(s.getTermStart()) || !start.isBefore(s.getTermEnd())) {
            throw new BusinessException("BAD_PERIOD", "Period start must fall inside the subscription term.");
        }
        int months = switch (s.getBillingInterval()) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            case "ANNUAL" -> 12;
            default -> -1;   // MULTI_YEAR: whole term
        };
        LocalDate end = months < 0 ? s.getTermEnd() : start.plusMonths(months);
        if (end.isAfter(s.getTermEnd())) end = s.getTermEnd();

        boolean duplicate = invoiceRepository.findBySubscriptionIdOrderByIdDesc(subscriptionId).stream()
                .anyMatch(i -> !"VOID".equals(i.getStatus()) && start.equals(i.getPeriodStart()));
        if (duplicate) {
            throw new BusinessException("ALREADY_INVOICED",
                    "An invoice for the period starting " + start + " already exists on this subscription.",
                    HttpStatus.CONFLICT);
        }

        double fraction = Money.termMonths(start, end) / Money.termMonths(s.getTermStart(), s.getTermEnd());
        boolean firstPeriod = start.equals(s.getTermStart());

        List<SubscriptionLine> subLines = subscriptionLineRepository.findBySubscriptionIdOrderByIdAsc(s.getId());
        List<BillingInvoiceLine> lines = new ArrayList<>();
        String periodLabel = " (" + start + " to " + end.minusDays(1) + ")";
        if (subLines.isEmpty()) {
            long amt = Math.round(s.getContractValueMinor() * fraction);
            lines.add(line(s.getPlanCode() + " subscription" + periodLabel, 1, amt));
        } else {
            for (SubscriptionLine l : subLines) {
                if (!l.isRecurring() && !firstPeriod) continue;
                long unit = l.isRecurring() ? Math.round(l.getUnitPriceMinor() * fraction) : l.getUnitPriceMinor();
                String d = (l.getDescription() != null ? l.getDescription() : l.getItemCode())
                        + (l.isRecurring() ? periodLabel : "");
                lines.add(line(d, l.getQuantity(), unit));
            }
        }

        Tenant t = tenantRepository.findById(s.getTenantId()).orElse(null);
        BillingInvoice inv = BillingInvoice.builder()
                .tenantId(s.getTenantId())
                .subscriptionId(s.getId())
                .status("DRAFT")
                .periodStart(start)
                .periodEnd(end)
                .currency(s.getCurrency())
                .createdBy(actorId)
                .build();
        applyDefaultTax(inv, t);
        invoiceRepository.save(inv);
        for (BillingInvoiceLine l : lines) l.setInvoiceId(inv.getId());
        invoiceLineRepository.saveAll(lines);
        recalc(inv, lines);
        return invoiceRepository.save(inv);
    }

    /** A free-form draft (services, one-off work) not tied to a subscription period. */
    @Transactional
    public BillingInvoice draftManual(Long tenantId, String currency, List<LineInput> in, String notes, Long actorId) {
        Tenant t = tenantRepository.findById(tenantId).orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        BillingInvoice inv = BillingInvoice.builder()
                .tenantId(tenantId).status("DRAFT")
                .currency(currency != null ? currency.toUpperCase() : "INR")
                .notes(notes).createdBy(actorId).build();
        applyDefaultTax(inv, t);
        invoiceRepository.save(inv);
        replaceLines(inv, in);
        return invoiceRepository.save(inv);
    }

    // ══════════════════════ EDIT DRAFT ═══════════════════════════════════════

    @Transactional
    public BillingInvoice updateDraft(Long id, List<LineInput> in, Integer taxRateBp, String taxLabel, String notes) {
        BillingInvoice inv = require(id);
        requireStatus(inv, "DRAFT");
        if (taxRateBp != null) {
            if (taxRateBp < 0 || taxRateBp > 5000) throw new BusinessException("BAD_TAX", "Tax rate out of range.");
            inv.setTaxRateBp(taxRateBp);
        }
        if (taxLabel != null) inv.setTaxLabel(taxLabel);
        if (notes != null) inv.setNotes(notes);
        if (in != null) replaceLines(inv, in);
        else recalc(inv, lines(inv.getId()));
        return invoiceRepository.save(inv);
    }

    @Transactional
    public void deleteDraft(Long id) {
        BillingInvoice inv = require(id);
        requireStatus(inv, "DRAFT");
        invoiceLineRepository.deleteByInvoiceId(id);
        invoiceRepository.delete(inv);
    }

    // ══════════════════════ LIFECYCLE ════════════════════════════════════════

    @Transactional
    public BillingInvoice issue(Long id, LocalDate issueDate) {
        BillingInvoice inv = require(id);
        requireStatus(inv, "DRAFT");
        if (inv.getTotalMinor() == null || inv.getTotalMinor() <= 0) {
            throw new BusinessException("EMPTY_INVOICE", "An invoice must have a positive total before it is issued.");
        }
        Tenant t = tenantRepository.findById(inv.getTenantId()).orElse(null);
        LocalDate day = issueDate != null ? issueDate : LocalDate.now();

        // Bill-to is frozen at issue — later profile edits never alter a sent invoice.
        if (t != null) {
            inv.setBillToName(t.getLegalName() != null ? t.getLegalName() : t.getName());
            inv.setBillToAddress(t.getBillingAddress());
            inv.setBillToTaxId(t.getTaxId());
        }
        String prefix = invoicePrefix + "/" + financialYear(day) + "/";
        long seq = invoiceRepository.countByInvoiceNumberStartingWith(prefix) + 1;
        inv.setInvoiceNumber(prefix + String.format("%04d", seq));
        inv.setIssueDate(day);
        inv.setDueDate(day.plusDays(paymentTermsDays));
        inv.setStatus("ISSUED");
        log.info("[BILLING] Invoice issued | {} tenant={} total={} {}", inv.getInvoiceNumber(),
                inv.getTenantId(), inv.getTotalMinor(), inv.getCurrency());
        return invoiceRepository.save(inv);
    }

    @Transactional
    public BillingInvoice markPaid(Long id, LocalDateTime paidAt, String reference) {
        BillingInvoice inv = require(id);
        requireStatus(inv, "ISSUED");
        inv.setStatus("PAID");
        inv.setPaidAt(paidAt != null ? paidAt : LocalDateTime.now());
        inv.setPaymentReference(reference);
        invoiceRepository.save(inv);

        // Clear PAST_DUE on the subscription once nothing else is overdue.
        if (inv.getSubscriptionId() != null) {
            subscriptionRepository.findById(inv.getSubscriptionId()).ifPresent(s -> {
                boolean stillOverdue = invoiceRepository.findBySubscriptionIdOrderByIdDesc(s.getId()).stream()
                        .anyMatch(i -> "OVERDUE".equals(effectiveStatus(i)));
                if ("PAST_DUE".equals(s.getStatus()) && !stillOverdue) {
                    s.setStatus("ACTIVE");
                    subscriptionRepository.save(s);
                }
            });
        }
        return inv;
    }

    @Transactional
    public BillingInvoice voidInvoice(Long id, String reason) {
        BillingInvoice inv = require(id);
        if (!"ISSUED".equals(inv.getStatus())) {
            throw new BusinessException("NOT_VOIDABLE",
                    "Only an issued, unpaid invoice can be voided. Delete a draft instead; credit-note a paid one.",
                    HttpStatus.CONFLICT);
        }
        if (reason == null || reason.isBlank()) throw new BusinessException("REASON_REQUIRED", "Give a reason for voiding.");
        inv.setStatus("VOID");
        inv.setVoidedAt(LocalDateTime.now());
        inv.setVoidReason(reason);
        return invoiceRepository.save(inv);
    }

    /** Daily: subscriptions with an overdue invoice become PAST_DUE. */
    @Transactional
    public int flagPastDue() {
        int n = 0;
        for (BillingInvoice i : invoiceRepository.findAllByOrderByIdDesc()) {
            if (!"OVERDUE".equals(effectiveStatus(i)) || i.getSubscriptionId() == null) continue;
            TenantSubscription s = subscriptionRepository.findById(i.getSubscriptionId()).orElse(null);
            if (s != null && "ACTIVE".equals(s.getStatus())) {
                s.setStatus("PAST_DUE");
                subscriptionRepository.save(s);
                n++;
            }
        }
        return n;
    }

    // ══════════════════════ INTERNALS ════════════════════════════════════════

    private void replaceLines(BillingInvoice inv, List<LineInput> in) {
        invoiceLineRepository.deleteByInvoiceId(inv.getId());
        List<BillingInvoiceLine> lines = new ArrayList<>();
        for (LineInput l : in == null ? List.<LineInput>of() : in) {
            if (l.description == null || l.description.isBlank()) throw new BusinessException("BAD_LINE", "Every line needs a description.");
            if (l.unitPriceMinor == null) throw new BusinessException("BAD_LINE", "Every line needs a unit price.");
            int q = l.quantity != null ? l.quantity : 1;
            if (q < 1) throw new BusinessException("BAD_LINE", "Quantity must be at least 1.");
            BillingInvoiceLine x = line(l.description, q, l.unitPriceMinor);
            x.setInvoiceId(inv.getId());
            if (l.hsnSac != null) x.setHsnSac(l.hsnSac);
            lines.add(x);
        }
        invoiceLineRepository.saveAll(lines);
        recalc(inv, lines);
    }

    private BillingInvoiceLine line(String description, int qty, long unit) {
        return BillingInvoiceLine.builder()
                .description(description).quantity(qty).unitPriceMinor(unit)
                .amountMinor((long) qty * unit)
                .hsnSac(defaultSac == null || defaultSac.isBlank() ? null : defaultSac)
                .build();
    }

    private void recalc(BillingInvoice inv, List<BillingInvoiceLine> lines) {
        long sub = lines.stream().mapToLong(BillingInvoiceLine::getAmountMinor).sum();
        long tax = Math.round(sub * (inv.getTaxRateBp() == null ? 0 : inv.getTaxRateBp()) / 10_000.0);
        inv.setSubtotalMinor(sub);
        inv.setTaxMinor(tax);
        inv.setTotalMinor(sub + tax);
    }

    private void applyDefaultTax(BillingInvoice inv, Tenant t) {
        String country = t != null && t.getCountry() != null ? t.getCountry().toUpperCase() : "IN";
        if ("IN".equals(country)) {
            inv.setTaxRateBp(1800);
            inv.setTaxLabel("GST 18%");
        } else {
            inv.setTaxRateBp(0);
            inv.setTaxLabel("Export of services — zero-rated (LUT)");
        }
    }

    private static void requireStatus(BillingInvoice inv, String status) {
        if (!status.equals(inv.getStatus())) {
            throw new BusinessException("BAD_INVOICE_STATUS",
                    "Invoice is " + inv.getStatus() + "; this needs " + status + ".", HttpStatus.CONFLICT);
        }
    }

    /** Indian FY label: 2026-04-01..2027-03-31 → "2026-27". */
    static String financialYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    @lombok.Data
    public static class LineInput {
        public String description;
        public Integer quantity;
        public Long unitPriceMinor;
        public String hsnSac;
    }
}