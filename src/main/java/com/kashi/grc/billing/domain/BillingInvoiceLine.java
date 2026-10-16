package com.kashi.grc.billing.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "billing_invoice_lines",
        indexes = @Index(name = "idx_invline_inv", columnList = "invoice_id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class BillingInvoiceLine extends BaseEntity {

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    /** SAC code for GST on software services (998314 etc.) */
    @Column(name = "hsn_sac", length = 12)
    private String hsnSac;

    @Column(name = "quantity", nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Column(name = "unit_price_minor", nullable = false)
    private Long unitPriceMinor;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;
}
