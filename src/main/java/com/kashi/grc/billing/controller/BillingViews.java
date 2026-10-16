package com.kashi.grc.billing.controller;

import com.kashi.grc.billing.domain.*;
import com.kashi.grc.billing.service.InvoiceService;

import java.util.*;

/** Entity → response map. Kept out of the entities so JSON shape is decided in one place. */
final class BillingViews {

    private BillingViews() {}

    static Map<String, Object> plan(BillingPlan p, List<BillingPlanPrice> prices) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("code", p.getCode());
        m.put("name", p.getName());
        m.put("tagline", p.getTagline());
        m.put("description", p.getDescription());
        m.put("tierRank", p.getTierRank());
        m.put("pricingMetric", p.getPricingMetric());
        m.put("includedVendors", p.getIncludedVendors());
        m.put("includedUsers", p.getIncludedUsers());
        m.put("includedEntities", p.getIncludedEntities());
        m.put("aiMonthlyTokens", p.getAiMonthlyTokens());
        m.put("featureKeys", p.getFeatureKeysCsv() == null ? List.of()
                : Arrays.stream(p.getFeatureKeysCsv().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        m.put("highlights", p.getHighlights() == null ? List.of()
                : Arrays.stream(p.getHighlights().split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        m.put("supportLevel", p.getSupportLevel());
        m.put("isPublic", p.isPublic());
        m.put("isActive", p.isActive());
        m.put("trialDays", p.getTrialDays());
        m.put("sortOrder", p.getSortOrder());
        m.put("audience", p.getAudience());
        m.put("priceDisplay", p.getPriceDisplay());
        m.put("ctaType", p.getCtaType());
        m.put("ctaLabel", p.getCtaLabel());
        m.put("badge", p.getBadge());
        m.put("includedFrameworks", p.getIncludedFrameworks());
        m.put("publicNote", p.getPublicNote());
        m.put("prices", prices.stream().map(BillingViews::price).toList());
        return m;
    }

    static Map<String, Object> price(BillingPlanPrice x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.getId());
        m.put("currency", x.getCurrency());
        m.put("billingInterval", x.getBillingInterval());
        m.put("unitAmountMinor", x.getUnitAmountMinor());
        m.put("overageUnitMinor", x.getOverageUnitMinor());
        m.put("aiOveragePerMillionMinor", x.getAiOveragePerMillionMinor());
        m.put("isActive", x.isActive());
        return m;
    }

    static Map<String, Object> subscription(TenantSubscription s, String tenantName, Long mrrMinor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("tenantId", s.getTenantId());
        m.put("tenantName", tenantName);
        m.put("planCode", s.getPlanCode());
        m.put("status", s.getStatus());
        m.put("changeType", s.getChangeType());
        m.put("termStart", s.getTermStart());
        m.put("termEnd", s.getTermEnd());
        m.put("seatsPurchased", s.getSeatsPurchased());
        m.put("vendorsPurchased", s.getVendorsPurchased());
        m.put("contractValueMinor", s.getContractValueMinor());
        m.put("currency", s.getCurrency());
        m.put("billingInterval", s.getBillingInterval());
        m.put("autoRenew", s.isAutoRenew());
        m.put("salesMotion", s.getSalesMotion());
        m.put("previousSubscriptionId", s.getPreviousSubscriptionId());
        m.put("churnedAt", s.getChurnedAt());
        m.put("churnReason", s.getChurnReason());
        m.put("churnNote", s.getChurnNote());
        m.put("cancelledAt", s.getCancelledAt());
        m.put("poReference", s.getPoReference());
        m.put("notes", s.getNotes());
        m.put("createdAt", s.getCreatedAt());
        m.put("mrrMinor", mrrMinor);
        return m;
    }

    static Map<String, Object> line(SubscriptionLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("lineType", l.getLineType());
        m.put("itemCode", l.getItemCode());
        m.put("description", l.getDescription());
        m.put("quantity", l.getQuantity());
        m.put("unitPriceMinor", l.getUnitPriceMinor());
        m.put("recurring", l.isRecurring());
        return m;
    }

    static Map<String, Object> invoice(BillingInvoice i, String tenantName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("tenantId", i.getTenantId());
        m.put("tenantName", tenantName);
        m.put("subscriptionId", i.getSubscriptionId());
        m.put("invoiceNumber", i.getInvoiceNumber());
        m.put("status", InvoiceService.effectiveStatus(i));
        m.put("storedStatus", i.getStatus());
        m.put("issueDate", i.getIssueDate());
        m.put("dueDate", i.getDueDate());
        m.put("periodStart", i.getPeriodStart());
        m.put("periodEnd", i.getPeriodEnd());
        m.put("currency", i.getCurrency());
        m.put("subtotalMinor", i.getSubtotalMinor());
        m.put("taxRateBp", i.getTaxRateBp());
        m.put("taxLabel", i.getTaxLabel());
        m.put("taxMinor", i.getTaxMinor());
        m.put("totalMinor", i.getTotalMinor());
        m.put("billToName", i.getBillToName());
        m.put("billToAddress", i.getBillToAddress());
        m.put("billToTaxId", i.getBillToTaxId());
        m.put("paidAt", i.getPaidAt());
        m.put("paymentReference", i.getPaymentReference());
        m.put("voidedAt", i.getVoidedAt());
        m.put("voidReason", i.getVoidReason());
        m.put("notes", i.getNotes());
        m.put("createdAt", i.getCreatedAt());
        return m;
    }

    static Map<String, Object> invoiceLine(BillingInvoiceLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("description", l.getDescription());
        m.put("hsnSac", l.getHsnSac());
        m.put("quantity", l.getQuantity());
        m.put("unitPriceMinor", l.getUnitPriceMinor());
        m.put("amountMinor", l.getAmountMinor());
        return m;
    }

    static Map<String, Object> cost(AcquisitionCost c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("periodMonth", c.getPeriodMonth());
        m.put("channel", c.getChannel());
        m.put("costType", c.getCostType());
        m.put("spendMinor", c.getSpendMinor());
        m.put("currency", c.getCurrency());
        m.put("notes", c.getNotes());
        return m;
    }

    static Map<String, Object> snapshot(TenantMrrSnapshot s, String tenantName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("tenantId", s.getTenantId());
        m.put("tenantName", tenantName);
        m.put("periodMonth", s.getPeriodMonth());
        m.put("planCode", s.getPlanCode());
        m.put("status", s.getStatus());
        m.put("mrrMinor", s.getMrrMinor());
        m.put("currency", s.getCurrency());
        m.put("mrrReportingMinor", s.getMrrReportingMinor());
        m.put("reportingCurrency", s.getReportingCurrency());
        m.put("seatsActive", s.getSeatsActive());
        m.put("seatsPurchased", s.getSeatsPurchased());
        m.put("vendorsActive", s.getVendorsActive());
        m.put("vendorsPurchased", s.getVendorsPurchased());
        m.put("aiCostMicros", s.getAiCostMicros());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }
}
