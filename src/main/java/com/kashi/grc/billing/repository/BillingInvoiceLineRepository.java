package com.kashi.grc.billing.repository;

import com.kashi.grc.billing.domain.BillingInvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingInvoiceLineRepository extends JpaRepository<BillingInvoiceLine, Long> {
    List<BillingInvoiceLine> findByInvoiceIdOrderByIdAsc(Long invoiceId);
    void deleteByInvoiceId(Long invoiceId);
}
