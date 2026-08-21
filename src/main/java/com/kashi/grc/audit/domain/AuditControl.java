package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AuditControl — a reusable control in the library.
 *
 * Mirrors AssessmentQuestion. Has NO foreign key to AuditSection, no weight,
 * no isMandatory, no order_no. Those live in AuditSectionControlMapping (join table)
 * because they differ per section context.
 *
 * One control can appear in many sections — zero duplication.
 *
 * tenant_id = null → global control (Platform Admin)
 * tenant_id = X    → private control for org X
 *
 * ── CONTROL TAG ──────────────────────────────────────────────────────────────
 * controlTag follows the exact same isolation pattern as AssessmentQuestion.questionTag.
 *
 * ISOLATION CONTRACT:
 *   - controlTag lives here (library, source of truth)
 *   - Snapshotted into AuditControlInstance.controlTagSnapshot at engagement instantiation
 *   - GuardRule.questionTag matches against the snapshot, never the live control
 *   - One rule with tag 'ENCRYPTION_AT_REST' covers every control in every template
 *     that carries that tag — across TPRM and Audit modules identically
 *
 * testType drives what the auditor does:
 *   DOCUMENT_REVIEW → auditor reviews uploaded documents
 *   INTERVIEW       → auditor records interview findings
 *   OBSERVATION     → auditor observes and records
 *   TECHNICAL_TEST  → auditor runs a technical test
 *   WALKTHROUGH     → auditor walks through the process
 *
 * controlCode: framework-specific identifier, e.g. "A.9.1.1", "CC6.1", "AC-1"
 */
@Entity
@Table(name = "audit_controls",
        indexes = {
                @Index(name = "idx_audit_ctrl_tenant", columnList = "tenant_id"),
                @Index(name = "idx_audit_ctrl_tag",    columnList = "control_tag"),
                @Index(name = "idx_audit_ctrl_code",   columnList = "control_code")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditControl extends GlobalOrTenantEntity {

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** e.g. "A.9.1.1", "CC6.1", "AC-1", "PCI DSS 8.3.6" */
    @Column(name = "control_code", length = 100)
    private String controlCode;

    @Column(name = "framework_ref", length = 100)
    private String frameworkRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "test_type", nullable = false, length = 30)
    @Builder.Default
    private TestType testType = TestType.DOCUMENT_REVIEW;

    /**
     * Semantic tag for KashiGuard rule matching.
     * Nullable — untagged controls are skipped by the guard system.
     * Snapshotted into AuditControlInstance.controlTagSnapshot at instantiation.
     * Follows identical isolation contract as AssessmentQuestion.questionTag.
     */
    @Column(name = "control_tag", length = 80)
    private String controlTag;

    /**
     * What evidence an auditor should expect to see for this control, authored
     * once in the library and snapshotted into AuditControlInstance at
     * engagement creation — the same contract as controlTag above.
     *
     * WHY IT LIVES ON THE CONTROL AND NOT ONLY ON TESTS
     *   Evidence guidance already existed on AuditTest, and the control
     *   instance Overview rolled it up from whichever tests were mapped. That
     *   works when a control is defined entirely by its tests, and produces
     *   nothing when a control has no tests mapped yet — which is most of a
     *   library before it is fully built out. Authoring at control level makes
     *   the guidance a property of the requirement rather than of the way it
     *   happens to be tested.
     *
     * PRECEDENCE: the control's own guidance wins; the test rollup is the
     * fallback when this is blank. See AuditInstanceController.getControlInstance.
     */
    @Column(name = "evidence_guidance", columnDefinition = "TEXT")
    private String evidenceGuidance;

    /**
     * KashiLink / UCF: the common control this framework requirement implements.
     * References common_controls.code — no FK, because the catalogue is global
     * (tenant_id NULL) while this table can be tenant-scoped.
     *
     * This is what makes one artefact satisfy SOC 2, ISO 27001 and RBI ITGRC at
     * once. control_tag remains for backward compatibility; once every row is
     * mapped, the tag becomes derived from this rather than authored.
     */
    @Column(name = "common_control_code", length = 40)
    private String commonControlCode;

    @Column(name = "created_by")
    private Long createdBy;

    public enum TestType {
        DOCUMENT_REVIEW,
        INTERVIEW,
        OBSERVATION,
        TECHNICAL_TEST,
        WALKTHROUGH
    }
}