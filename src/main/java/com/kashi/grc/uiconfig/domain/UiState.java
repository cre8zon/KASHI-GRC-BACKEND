package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * DB-driven UI states for every screen.
 * Stores success, error, empty, loading state definitions.
 * No code deploy needed to change messaging, icons, or CTAs.
 *
 * state_type: SUCCESS | ERROR | EMPTY | LOADING | FORBIDDEN | NOT_FOUND
 * screen_key: 'vendor_list', 'assessment_view', 'user_create', etc.
 */
@Entity
@Table(name = "ui_states",
       uniqueConstraints = @UniqueConstraint(columnNames = {"screen_key", "state_type", "tenant_id"}))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class UiState extends BaseEntity {

    @Column(name = "screen_key", nullable = false, length = 100)
    private String screenKey;

    /** SUCCESS, ERROR, EMPTY, LOADING, FORBIDDEN, NOT_FOUND */
    @Column(name = "state_type", nullable = false, length = 30)
    private String stateType;

    /** Heading shown to user. e.g. 'No vendors found', 'Tenant created!' */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** Subtext / description */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Lucide icon name: 'PackageOpen', 'CheckCircle', 'AlertTriangle' */
    @Column(name = "icon", length = 100)
    private String icon;

    /** Semantic color: 'green', 'red', 'amber', 'blue', 'gray' */
    @Column(name = "color_tag", length = 30)
    @Builder.Default
    private String colorTag = "gray";

    /** Label on primary CTA button. NULL = no CTA */
    @Column(name = "cta_label", length = 100)
    private String ctaLabel;

    /** Route or action key for CTA. e.g. '/tprm/vendors/new' or 'retry' */
    @Column(name = "cta_action", length = 255)
    private String ctaAction;

    /** Secondary CTA label */
    @Column(name = "secondary_cta_label", length = 100)
    private String secondaryCtaLabel;

    @Column(name = "secondary_cta_action", length = 255)
    private String secondaryCtaAction;

    /** NULL = global; tenant override supported */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;
}
