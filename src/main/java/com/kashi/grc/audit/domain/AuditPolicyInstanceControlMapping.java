package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AuditPolicyInstanceControlMapping — runtime many-to-many between policy instances and control instances.
 *
 * Created during snapshotTemplate() — mirrors AuditPolicyControlMapping at library level.
 *
 * ── PURPOSE ───────────────────────────────────────────────────────────────────
 * Allows the control drawer's Policies tab to show:
 *   "This control is covered by 2 policies in this engagement."
 *
 * Allows the policy instance detail to show:
 *   "This policy covers 8 controls in this engagement."
 *
 * ── ISOLATION ────────────────────────────────────────────────────────────────
 * Library changes to AuditPolicyControlMapping NEVER affect this table.
 * All original IDs are audit trail only — NEVER used in runtime joins.
 *
 * ── REVIEW STATUS ────────────────────────────────────────────────────────────
 * reviewContribution tracks whether the auditor has accepted this policy as
 * satisfying the specific control in this engagement.
 *   PENDING   = auditor hasn't reviewed this policy→control link yet
 *   SATISFIES = auditor confirms this policy satisfies the control
 *   GAPS      = policy doesn't fully satisfy — noted as a gap
 */
@Entity
@Table(name = "audit_policy_instance_control_mappings",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_policy_inst_ctrl_inst",
        columnNames = {"policy_instance_id", "control_instance_id"}
    ),
    indexes = {
        @Index(name = "idx_apicm_policy",   columnList = "policy_instance_id"),
        @Index(name = "idx_apicm_control",  columnList = "control_instance_id"),
        @Index(name = "idx_apicm_tenant",   columnList = "tenant_id"),
        @Index(name = "idx_apicm_eng",      columnList = "engagement_id")
    }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditPolicyInstanceControlMapping extends TenantAwareEntity {

    @Column(name = "policy_instance_id", nullable = false)
    private Long policyInstanceId;

    @Column(name = "control_instance_id", nullable = false)
    private Long controlInstanceId;

    @Column(name = "engagement_id", nullable = false)
    private Long engagementId;

    /** Snapshotted from AuditPolicyControlMapping.mappingType */
    @Column(name = "mapping_type_snapshot", length = 20)
    private String mappingTypeSnapshot;

    /** Snapshotted mapping note */
    @Column(name = "mapping_note_snapshot", columnDefinition = "TEXT")
    private String mappingNoteSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_contribution", length = 20)
    @Builder.Default
    private ReviewContribution reviewContribution = ReviewContribution.PENDING;

    /** Audit trail only */
    @Column(name = "original_policy_id")
    private Long originalPolicyId;

    @Column(name = "original_control_id")
    private Long originalControlId;

    public enum ReviewContribution {
        PENDING,
        SATISFIES,
        GAPS
    }
}
