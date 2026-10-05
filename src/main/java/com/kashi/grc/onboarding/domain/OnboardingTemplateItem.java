package com.kashi.grc.onboarding.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/** One line of a checklist template. */
@Entity
@Table(name = "onboarding_template_items",
        indexes = {
                @Index(name = "idx_onb_item_tpl",    columnList = "template_id,is_active,sort_order"),
                @Index(name = "idx_onb_item_tenant", columnList = "tenant_id"),
        })
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class OnboardingTemplateItem extends TenantAwareEntity {

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    /** SCREENING | ACCESS | EQUIPMENT | POLICY | TRAINING | ADMIN */
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String category = "ADMIN";

    /**
     * A ROLE, not a person. "IT" issues the laptop whoever is on shift, and
     * naming an individual in a template means it rots the first time they
     * leave.
     */
    @Column(name = "owner_role", length = 100)
    private String ownerRole;

    /**
     * Days from start_date. NEGATIVE IS THE POINT: a background check due at
     * -7 is due a week before the person starts, which is when it is useful.
     */
    @Column(name = "due_days_offset", nullable = false)
    @Builder.Default
    private Integer dueDaysOffset = 0;

    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private boolean isRequired = true;

    /** An NDA marked done with nothing attached is a claim, not evidence. */
    @Column(name = "requires_evidence", nullable = false)
    @Builder.Default
    private boolean requiresEvidence = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;
}