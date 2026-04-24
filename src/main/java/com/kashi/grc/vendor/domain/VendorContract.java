package com.kashi.grc.vendor.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "vendor_contracts")
@Getter @Setter @lombok.experimental.SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class VendorContract extends TenantAwareEntity {

    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    @Column(name = "contract_number")
    private String contractNumber;

    @Column(name = "contract_type")
    private String contractType;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "renewal_date")
    private LocalDate renewalDate;

    @Column(name = "contract_value", precision = 15, scale = 2)
    private BigDecimal contractValue;

    @Column(name = "status")
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "document_id")
    private Long documentId;
}
