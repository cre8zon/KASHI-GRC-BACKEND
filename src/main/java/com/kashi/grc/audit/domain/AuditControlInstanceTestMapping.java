package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AuditControlInstanceTestMapping — runtime many-to-many between control instances and test instances.
 *
 * ── RELATIONSHIP ──────────────────────────────────────────────────────────────
 * AuditControlInstance (many) ←──→ AuditTestInstance (many)
 *
 * Created during snapshotTemplate() — mirrors AuditControlTestMapping at library level.
 *
 * ── ISOLATION ────────────────────────────────────────────────────────────────
 * This is the RUNTIME mapping — frozen at engagement creation.
 * Changes to AuditControlTestMapping (library) NEVER affect this table.
 *
 * ── RESULT DERIVATION ────────────────────────────────────────────────────────
 * When AuditTestInstance.testResult changes:
 *   AuditEngagementService.deriveControlResult(controlInstanceId) is called.
 *   It queries this table for all test instance IDs linked to the control.
 *   It then reads each test's result and applies the derivation rule:
 *
 *   All required tests PASS              → control = EFFECTIVE
 *   Any required test FAILS              → control = INEFFECTIVE
 *   Some required pass, some NOT_RUN     → control = PARTIALLY_EFFECTIVE
 *   No required tests run                → control = NOT_TESTED
 *
 * ── MANUAL OVERRIDE ──────────────────────────────────────────────────────────
 * manualOverrideAllowed: set to true when the control has no tests.
 * When true, the auditor can still manually set testResult on the control.
 * When false (tests exist), testResult is always derived — never manually set.
 * This prevents conflicting signals between test results and manual entry.
 */
@Entity
@Table(name = "audit_control_instance_test_mappings",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_ctrl_inst_test_inst",
        columnNames = {"control_instance_id", "test_instance_id"}
    ),
    indexes = {
        @Index(name = "idx_acitm_control", columnList = "control_instance_id"),
        @Index(name = "idx_acitm_test",    columnList = "test_instance_id"),
        @Index(name = "idx_acitm_tenant",  columnList = "tenant_id"),
        @Index(name = "idx_acitm_eng",     columnList = "engagement_id")
    }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditControlInstanceTestMapping extends TenantAwareEntity {

    @Column(name = "control_instance_id", nullable = false)
    private Long controlInstanceId;

    @Column(name = "test_instance_id", nullable = false)
    private Long testInstanceId;

    @Column(name = "engagement_id", nullable = false)
    private Long engagementId;

    /** Snapshotted from AuditControlTestMapping.isRequired at instantiation time */
    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private boolean isRequired = true;

    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;

    /** Snapshotted note from the library mapping */
    @Column(name = "mapping_note_snapshot", columnDefinition = "TEXT")
    private String mappingNoteSnapshot;

    /**
     * Library IDs — audit trail only.
     * originalControlId + originalTestId trace back to library entities.
     * NEVER used in runtime joins.
     */
    @Column(name = "original_control_id")
    private Long originalControlId;

    @Column(name = "original_test_id")
    private Long originalTestId;
}
