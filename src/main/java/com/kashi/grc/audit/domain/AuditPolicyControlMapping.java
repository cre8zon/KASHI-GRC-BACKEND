package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AuditPolicyControlMapping — library-level many-to-many between policies and controls.
 *
 * AuditPolicy (many) ←──→ AuditControl (many)
 *
 * One policy satisfies many controls:
 *   "Encryption Policy" → ISO A.10.1, SOC 2 CC6.7, GDPR Art.32
 *
 * One control can be satisfied by many policies:
 *   ISO A.9.1 "Access Control" ← "Access Control Policy" + "Acceptable Use Policy"
 *
 * ── MAPPING NOTE ─────────────────────────────────────────────────────────────
 * mappingNote explains HOW this policy satisfies this control.
 * e.g. "Section 4.2 of this policy directly addresses the control requirement"
 *
 * ── MAPPING TYPE ─────────────────────────────────────────────────────────────
 * DIRECT    = policy directly documents the control requirement (strongest link)
 * PARTIAL   = policy partially addresses the requirement — other policies needed too
 * REFERENCE = policy references another authoritative source that satisfies the control
 *
 * ── ISOLATION ────────────────────────────────────────────────────────────────
 * AuditPolicyInstanceControlMapping is the RUNTIME snapshot — frozen at engagement.
 * Library mapping changes NEVER affect running engagements.
 */
@Entity
@Table(name = "audit_policy_control_mappings",
        // tenant_id is PART OF THE KEY.
        //
        // Without it a (policy, control) pair could exist exactly once across the
        // whole platform, so the global row occupied the slot and NO tenant could
        // ever write a row for that pair — which made the EXCLUDED mechanism
        // impossible: excluding a platform policy inserts a tenant row for the
        // same (policy, control) and collided with the global mapping.
        //   Duplicate entry '10-327' for key 'uk_policy_control'
        //
        // The intent was always "one mapping per policy per control PER OWNER" —
        // the tenant column was simply left out of the key.
        //
        // ddl-auto=update does NOT alter an existing unique index, so the
        // accompanying migration must be run by hand.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_policy_control_tenant",
                columnNames = {"policy_id", "control_id", "tenant_id"}
        ),
        indexes = {
                @Index(name = "idx_apcm_policy",  columnList = "policy_id"),
                @Index(name = "idx_apcm_control", columnList = "control_id"),
                @Index(name = "idx_apcm_tenant",  columnList = "tenant_id")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditPolicyControlMapping extends GlobalOrTenantEntity {

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "control_id", nullable = false)
    private Long controlId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_type", nullable = false, length = 20)
    @Builder.Default
    private MappingType mappingType = MappingType.DIRECT;

    @Column(name = "mapping_note", columnDefinition = "TEXT")
    private String mappingNote;

    @Column(name = "created_by")
    private Long createdBy;

    public enum MappingType {
        DIRECT,    // policy directly satisfies the control
        PARTIAL,   // partial coverage — other evidence needed
        REFERENCE, // policy references another source

        /**
         * Not a mapping — a SUPPRESSION.
         *
         * A tenant-owned row with EXCLUDED, pointing at a PLATFORM policy, means
         * "this platform policy does not apply to us on this control". The global
         * row is left untouched — a tenant can never delete platform data — and
         * the exclusion is resolved away at read time.
         *
         * Modelled as a mapping row rather than a boolean column because it is
         * audit evidence in its own right: it carries who excluded it, when, and
         * why (mappingNote). "Why is the platform AUP missing from this control?"
         * is a question an auditor asks, and a silent gap is a finding.
         */
        EXCLUDED
    }
}