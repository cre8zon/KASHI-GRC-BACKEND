package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AuditTest — a named, reusable test in the audit library.
 *
 * ── WHAT IS A TEST? ──────────────────────────────────────────────────────────
 * A test is the specific procedure used to verify a control requirement.
 * One test can verify MANY controls (many-to-many via AuditControlTestMapping).
 * One control can require MANY tests.
 *
 * Examples:
 *   "MFA enforced on all production systems"   → satisfies ISO A.9.4.2, SOC 2 CC6.1, NIST PR.AC-7
 *   "Quarterly access review completed"         → satisfies ISO A.9.2.5, SOC 2 CC6.3
 *   "Encryption at rest verified"               → satisfies ISO A.10.1, SOC 2 CC6.7
 *
 * ── ISOLATION ────────────────────────────────────────────────────────────────
 * This is the LIBRARY entity — mutable, shared, referenced.
 * AuditTestInstance is the RUNTIME snapshot — frozen at engagement creation.
 * Follows the identical isolation pattern as AuditControl → AuditControlInstance.
 *
 * ── AUTOMATION ───────────────────────────────────────────────────────────────
 * automationType = AUTOMATED → KashiGuard runs the check, sets testResult automatically
 * automationType = MANUAL    → human uploads evidence and manually marks pass/fail
 * automationType = HYBRID    → automated check is a signal, human makes final call
 *
 * automationKey: the KashiGuard rule key that runs this test automatically.
 *   e.g. "kashiguard.mfa_enforced", "kashiguard.encryption_at_rest"
 *   Null = no automation.
 *
 * ── TAG SYSTEM ───────────────────────────────────────────────────────────────
 * controlTag follows the same namespace as AuditControl.controlTag.
 * When evidence is uploaded with tag 'MFA', EvidenceReuseEngine auto-links it
 * to all AuditTestInstance rows with controlTagSnapshot='MFA' — zero manual work.
 *
 * ── FREQUENCY ────────────────────────────────────────────────────────────────
 * Drives how often this test should run / evidence should be refreshed.
 * CONTINUOUS = automated check runs in real-time (KashiGuard)
 * ANNUAL     = evidence collected once per year (e.g. penetration test)
 *
 * ── CSV IMPORT ───────────────────────────────────────────────────────────────
 * Extended AuditCsvImportService to import TEST rows.
 * CSV column: type=TEST, name, description, test_type, automation_type,
 *             automation_key, frequency, control_tag, framework_ref
 * Followed by CONTROL_TEST_MAPPING rows to link controls ↔ tests.
 */
@Entity
@Table(name = "audit_tests",
        indexes = {
                @Index(name = "idx_audit_test_tenant",   columnList = "tenant_id"),
                @Index(name = "idx_audit_test_tag",      columnList = "control_tag"),
                @Index(name = "idx_audit_test_auto_key", columnList = "automation_key"),
                @Index(name = "idx_audit_test_type",     columnList = "automation_type")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditTest extends GlobalOrTenantEntity {

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Human-readable test reference. e.g. "TEST-001"
     * Useful for cross-referencing in reports.
     */
    @Column(name = "test_ref", length = 30)
    private String testRef;

    /**
     * Framework-specific test identifier, e.g. "SOC2-CC6.1-T1", "ISO-A.9.4.2-T2"
     * Optional — used when the framework defines specific test procedures.
     */
    @Column(name = "framework_test_id", length = 100)
    private String frameworkTestId;

    @Column(name = "framework_ref", length = 100)
    private String frameworkRef;

    /**
     * Semantic tag — same namespace as AuditControl.controlTag.
     * Used by EvidenceReuseEngine to auto-link evidence to test instances.
     * Also used to auto-suggest tests when a control is added to a template.
     */
    @Column(name = "control_tag", length = 80)
    private String controlTag;

    // ── Automation ────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "automation_type", nullable = false, length = 20)
    @Builder.Default
    private AutomationType automationType = AutomationType.MANUAL;

    /**
     * KashiGuard rule key that runs this test automatically.
     * e.g. "kashiguard.mfa_enforced"
     * Null = no automation — human must provide evidence.
     */
    @Column(name = "automation_key", length = 100)
    private String automationKey;

    // ── Frequency ─────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    @Builder.Default
    private Frequency frequency = Frequency.ANNUAL;

    // ── Test execution config ─────────────────────────────────────────────────

    /**
     * The specific procedure the auditor follows to run this test.
     * e.g. "1. Open the IAM console. 2. Navigate to MFA settings..."
     * Snapshotted into AuditTestInstance.testProcedureSnapshot.
     */
    @Column(name = "test_procedure", columnDefinition = "TEXT")
    private String testProcedure;

    /**
     * What specific evidence satisfies this test.
     * e.g. "Screenshot of MFA settings showing 'Required for all users'"
     */
    @Column(name = "evidence_guidance", columnDefinition = "TEXT")
    private String evidenceGuidance;

    @Column(name = "created_by")
    private Long createdBy;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum AutomationType {
        /** KashiGuard runs the check — result is set automatically */
        AUTOMATED,
        /** Human uploads evidence and marks pass/fail */
        MANUAL,
        /** Automated check is a leading signal; human makes the final call */
        HYBRID,
        /** Test requires a specific document/screenshot to be uploaded as evidence */
        UPLOAD
    }

    public enum Frequency {
        CONTINUOUS, // real-time (automated checks only)
        DAILY,
        WEEKLY,
        MONTHLY,
        QUARTERLY,
        SEMI_ANNUAL,
        ANNUAL
    }
}