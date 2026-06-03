package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AuditControlTestMapping — library-level many-to-many between controls and tests.
 *
 * ── RELATIONSHIP ──────────────────────────────────────────────────────────────
 * AuditControl (many) ←──→ AuditTest (many)
 *
 * One test satisfies many controls:
 *   "MFA enforced" → ISO A.9.4.2, SOC 2 CC6.1, NIST PR.AC-7
 *
 * One control requires many tests:
 *   ISO A.9.1 → "MFA enforced" + "Quarterly access review" + "Privileged access documented"
 *
 * ── ISOLATION ────────────────────────────────────────────────────────────────
 * This is a LIBRARY mapping — it defines which tests exist for a control.
 * AuditControlInstanceTestMapping is the RUNTIME snapshot — frozen at engagement creation.
 * The snapshot captures: which tests were required for which controls, at snapshot time.
 *
 * ── isRequired ───────────────────────────────────────────────────────────────
 * true  = control cannot be marked EFFECTIVE unless this test passes
 * false = test is advisory — passing it contributes to the control but doesn't block it
 *
 * ── Control status derivation ────────────────────────────────────────────────
 * AuditEngagementService.deriveControlResult():
 *   if all required tests PASS           → EFFECTIVE
 *   if any required test FAILS           → INEFFECTIVE
 *   if some required tests pass, some not run → PARTIALLY_EFFECTIVE
 *   if no required tests run             → NOT_TESTED
 * This replaces the manual testResult selector for controls that have tests.
 */
@Entity
@Table(name = "audit_control_test_mappings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ctrl_test_mapping",
                columnNames = {"control_id", "test_id"}
        ),
        indexes = {
                @Index(name = "idx_actm_control", columnList = "control_id"),
                @Index(name = "idx_actm_test",    columnList = "test_id"),
                @Index(name = "idx_actm_tenant",  columnList = "tenant_id")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditControlTestMapping extends GlobalOrTenantEntity {

    /** AuditControl.id — NOT a FK, follows zero-FK convention */
    @Column(name = "control_id", nullable = false)
    private Long controlId;

    /** AuditTest.id — NOT a FK */
    @Column(name = "test_id", nullable = false)
    private Long testId;

    /**
     * true  = control cannot be EFFECTIVE unless this test passes
     * false = advisory test — contributes but doesn't block EFFECTIVE status
     */
    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private boolean isRequired = true;

    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;

    /** Optional note explaining why this test is required for this control */
    @Column(name = "mapping_note", columnDefinition = "TEXT")
    private String mappingNote;

    @Column(name = "created_by")
    private Long createdBy;
}