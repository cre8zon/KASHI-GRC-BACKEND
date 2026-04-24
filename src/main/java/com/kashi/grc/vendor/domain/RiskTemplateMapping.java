package com.kashi.grc.vendor.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "risk_template_mapping")
@Getter @Setter @lombok.experimental.SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class RiskTemplateMapping extends GlobalOrTenantEntity {

    @Column(name = "min_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal minScore;

    @Column(name = "max_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxScore;

    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "tier_label")
    private String tierLabel;
}
