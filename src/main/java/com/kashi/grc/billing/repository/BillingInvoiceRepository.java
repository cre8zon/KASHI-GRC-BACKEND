package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.BillingInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, Long> {
    List<BillingInvoice> findByTenantIdOrderByIdDesc(Long tenantId);
    List<BillingInvoice> findAllByOrderByIdDesc();
    List<BillingInvoice> findBySubscriptionIdOrderByIdDesc(Long subscriptionId);

    /** Numbered invoices in one financial-year series (prefix "KG/2026-27/") — the next sequence is this + 1. */
    long countByInvoiceNumberStartingWith(String prefix);
}