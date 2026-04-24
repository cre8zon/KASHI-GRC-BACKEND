package com.kashi.grc.vendor.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "vendor_tiers")
@Getter @Setter @lombok.experimental.SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class VendorTier extends TenantAwareEntity {

    @Column(name = "tier_name", nullable = false)
    private String tierName;

    @Column(name = "assessment_frequency_months")
    private Integer assessmentFrequencyMonths;

    @Column(name = "requires_soc2")
    @Builder.Default
    private boolean requiresSoc2 = false;

    @Column(name = "requires_iso27001")
    @Builder.Default
    private boolean requiresIso27001 = false;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
