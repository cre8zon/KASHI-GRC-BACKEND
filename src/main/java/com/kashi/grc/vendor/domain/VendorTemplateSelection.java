package com.kashi.grc.vendor.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Stores the assessment template candidates surfaced by QUEUE_ASSESSMENT_CANDIDATES
 * and the subsequent human selection made on the "Select Assessment Template" step.
 *
 * Lifecycle:
 *   1. QUEUE_ASSESSMENT_CANDIDATES fires → row inserted with candidateTemplateIds,
 *      selectedTemplateId = null (pending human pick).
 *      Exception: if only 1 template is mapped for the score, selectedTemplateId is
 *      set immediately and the SELECT step auto-advances.
 *
 *   2. ORG_ADMIN / ORG_OWNER opens the Select Assessment Template task page
 *      → calls POST /v1/assessments/template-selection/select
 *      → selectedTemplateId, selectedByUserId, selectedAt are filled.
 *
 *   3. EXECUTE_ASSESSMENT fires → reads selectedTemplateId from this row
 *      and instantiates that template.
 */
@Entity
@Table(name = "vendor_template_selection")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class VendorTemplateSelection extends BaseEntity {

    @Column(name = "workflow_instance_id", nullable = false, unique = true)
    private Long workflowInstanceId;

    /** The step_instances.id of the QUEUE_ASSESSMENT_CANDIDATES step that created this row */
    @Column(name = "step_instance_id")
    private Long stepInstanceId;

    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    @Column(name = "tenant_id")
    private Long tenantId;

    /** e.g. LOW / MEDIUM / HIGH / CRITICAL — from the matching RiskTemplateMapping rows */
    @Column(name = "risk_tier_label", length = 50)
    private String riskTierLabel;

    /**
     * JSON array of templateIds available for selection, e.g. [101,102,103].
     * Set by QUEUE_ASSESSMENT_CANDIDATES. Never changed after creation.
     */
    @Column(name = "candidate_template_ids", columnDefinition = "TEXT", nullable = false)
    private String candidateTemplateIds;

    /**
     * The templateId chosen by ORG_ADMIN / ORG_OWNER.
     * Null until the human makes the selection.
     * When only one candidate exists, QUEUE_ASSESSMENT_CANDIDATES pre-fills this
     * so the SELECT step can auto-approve without human interaction.
     */
    @Column(name = "selected_template_id")
    private Long selectedTemplateId;

    @Column(name = "selected_by_user_id")
    private Long selectedByUserId;

    @Column(name = "selected_at")
    private LocalDateTime selectedAt;
}