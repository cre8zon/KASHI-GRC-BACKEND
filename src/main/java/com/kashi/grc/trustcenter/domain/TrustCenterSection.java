package com.kashi.grc.trustcenter.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/** A block on the page: certifications, subprocessors, FAQ, or free text. */
@Entity
@Table(name = "trust_center_sections",
       indexes = @Index(name = "idx_tcs_center", columnList = "trust_center_id,is_visible,sort_order"))
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class TrustCenterSection extends TenantAwareEntity {

    @Column(name = "trust_center_id", nullable = false) private Long trustCenterId;

    /** CERTIFICATIONS | CONTROLS | SUBPROCESSORS | DOCUMENTS | FAQ | CUSTOM */
    @Column(name = "section_type", nullable = false, length = 30)
    @Builder.Default
    private String sectionType = "CUSTOM";

    @Column(nullable = false, length = 200) private String title;

    @Column(name = "body_md", columnDefinition = "TEXT") private String bodyMd;

    /** JSON rather than more tables: the shape differs per section type and
     *  none of it is queried — it is rendered. */
    @Column(name = "items_json", columnDefinition = "JSON") private String itemsJson;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_visible", nullable = false)
    @Builder.Default
    private boolean isVisible = true;
}
