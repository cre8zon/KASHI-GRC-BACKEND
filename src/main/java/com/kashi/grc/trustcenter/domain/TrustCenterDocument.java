package com.kashi.grc.trustcenter.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/** A document offered on the trust page, and how hard it is to get. */
@Entity
@Table(
        name = "trust_center_documents",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_tcd", columnNames = {"trust_center_id", "document_id"}),
        indexes = @Index(name = "idx_tcd_center", columnList = "trust_center_id,is_visible,sort_order"))
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class TrustCenterDocument extends TenantAwareEntity {

    @Column(name = "trust_center_id", nullable = false) private Long trustCenterId;
    @Column(name = "document_id", nullable = false)     private Long documentId;

    @Column(nullable = false, length = 200) private String title;
    @Column(length = 500) private String description;
    @Column(length = 50)  private String category;

    /**
     * PUBLIC | EMAIL_GATED | NDA_REQUIRED | ON_REQUEST
     *
     * ON_REQUEST is not listed on the page at all. Worth having: some documents
     * should not even be advertised, and "we have one, ask us" is a different
     * posture from showing a locked row.
     */
    @Column(name = "access_level", nullable = false, length = 20)
    @Builder.Default
    private String accessLevel = "EMAIL_GATED";

    /** Past this date the document stops being offered, rather than quietly
     *  serving a stale SOC 2 for another year. */
    @Column(name = "valid_until") private LocalDate validUntil;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_visible", nullable = false)
    @Builder.Default
    private boolean isVisible = true;

    @Transient
    public boolean isCurrentlyOffered() {
        return isVisible && (validUntil == null || !validUntil.isBefore(LocalDate.now()));
    }
}
