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
        uniqueConstraints = @UniqueConstraint(
                name = "uk_policy_control",
                columnNames = {"policy_id", "control_id"}
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
        REFERENCE  // policy references another source
    }
}