package com.kashi.grc.ucf.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * The crosswalk — which framework citation a common control satisfies.
 *
 * ── NOT READ AT MATCH TIME ──────────────────────────────────────────────────
 * The reuse engine never touches this table. Matching runs off
 * audit_controls.common_control_code plus the catalogue hierarchy. This is the
 * REPORTING layer: "which frameworks does evidence for IAM-02.3 cover", and
 * the authority behind that claim when an auditor asks.
 *
 * It also covers frameworks a tenant has NOT imported a library for, which is
 * why it exists separately rather than being derived from audit_controls.
 *
 * ── RELATIONSHIP SEMANTICS (STRM / NIST IR 8477) ────────────────────────────
 * Direction is always common control -> framework requirement. Set theory, not
 * a homegrown DIRECT/PARTIAL scale, because the terms have to survive an
 * auditor asking why one artefact counted for two frameworks.
 *
 *   EQUAL            same scope both ways
 *   SUPERSET_OF      the common control is BROADER than the requirement
 *                    → evidence fully satisfies it
 *   SUBSET_OF        the common control is NARROWER
 *                    → evidence contributes but does not satisfy alone
 *   INTERSECTS_WITH  partial overlap in both directions
 *
 * SUBSET_OF dominates in practice, and that is not a defect. SOC 2 CC6.1 is the
 * whole logical-access apparatus; MFA on admin accounts is one part of it. So
 * transitive matching should surface CONTRIBUTION and remaining gaps, not
 * declare blanket satisfaction. Phase 3 reads this field to decide link status.
 */
@Entity
@Table(name = "common_control_mappings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ccm",
                columnNames = {"common_control_code", "framework_ref", "citation"}),
        indexes = {
                @Index(name = "idx_ccm_cc",  columnList = "common_control_code"),
                @Index(name = "idx_ccm_fw",  columnList = "framework_ref,citation"),
                @Index(name = "idx_ccm_rel", columnList = "relationship")
        })
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CommonControlMapping extends BaseEntity {

    @Column(name = "common_control_code", nullable = false, length = 40)
    private String commonControlCode;

    /** Must match audit_controls.framework_ref exactly — 'ISO27001', 'SOC2'. */
    @Column(name = "framework_ref", nullable = false, length = 100)
    private String frameworkRef;

    /** 'CC6.1', 'A.8.5', 'S.8(5)'. */
    @Column(name = "citation", nullable = false, length = 60)
    private String citation;

    @Column(name = "citation_title", length = 500)
    private String citationTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", nullable = false, length = 20)
    private Relationship relationship;

    /** Why this mapping holds. Written for the auditor who challenges it. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** KASHI = hand-authored, DERIVED = generated from the library, SCF = imported. */
    @Column(name = "source", nullable = false, length = 20)
    @lombok.Builder.Default
    private String source = "KASHI";

    @Column(name = "is_active", nullable = false)
    @lombok.Builder.Default
    private Boolean active = true;

    @Column(name = "tenant_id")
    private Long tenantId;

    public enum Relationship {
        EQUAL,
        SUPERSET_OF,
        SUBSET_OF,
        INTERSECTS_WITH;

        /**
         * Whether evidence for the common control FULLY satisfies the
         * requirement on its own. Phase 3 uses this to decide whether a
         * transitive link is offered as coverage or only as contribution.
         */
        public boolean fullySatisfies() {
            return this == EQUAL || this == SUPERSET_OF;
        }
    }
}