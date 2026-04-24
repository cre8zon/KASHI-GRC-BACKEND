package com.kashi.grc.vendor.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "vendors")
@Getter @Setter @lombok.experimental.SuperBuilder @NoArgsConstructor @AllArgsConstructor
public class Vendor extends TenantAwareEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "country")
    private String country;

    @Column(name = "industry")
    private String industry;

    @Column(name = "website")
    private String website;

    @Column(name = "primary_contact_email")
    private String primaryContactEmail;

    @Column(name = "risk_classification")
    private String riskClassification;

    @Column(name = "criticality")
    private String criticality;

    @Column(name = "data_access_level")
    private String dataAccessLevel;

    @Column(name = "services_provided", columnDefinition = "TEXT")
    private String servicesProvided;

    @Column(name = "current_risk_score", precision = 5, scale = 2)
    private BigDecimal currentRiskScore;

    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "status")
    @Builder.Default
    private String status = "ONBOARDING";

    @Column(name = "is_deleted")
    @Builder.Default
    private boolean isDeleted = false;
}
