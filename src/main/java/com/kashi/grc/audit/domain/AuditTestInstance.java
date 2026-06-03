package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * AuditTestInstance — 100% isolated runtime snapshot of one AuditTest.
 *
 * ── FULL ISOLATION STACK ─────────────────────────────────────────────────────
 *
 *   AuditTest (library, mutable)
 *       ↓ snapshotted at engagement creation time
 *   AuditTestInstance (THIS ENTITY — frozen)
 *       ↓ linked to many
 *   AuditControlInstance (via AuditControlInstanceTestMapping)
 *
 * Changes to the library AuditTest NEVER affect running engagements.
 * A new version of the test is a new AuditTestInstance in the next engagement.
 *
 * ── ZERO FK RULE ─────────────────────────────────────────────────────────────
 * originalTestId = plain Long, audit trail only.
 * engagementId   = plain Long, NOT a @ManyToOne relationship.
 *
 * ── RESULT LIFECYCLE ─────────────────────────────────────────────────────────
 * NOT_RUN   → initial state
 * PASS      → test completed successfully (manual or automated)
 * FAIL      → test failed (manual or automated)
 * EXCEPTION → test could not be completed (scope exclusion, system unavailable)
 *
 * When result changes to PASS or FAIL:
 *   1. All linked AuditControlInstance rows are re-evaluated
 *   2. controlInstance.testResult is derived from all required tests (auto-derive)
 *
 * ── AUTOMATED TESTS ──────────────────────────────────────────────────────────
 * If automationTypeSnapshot = AUTOMATED:
 *   - KashiGuard sets result + runAt + runBySystem=true automatically
 *   - Human cannot override unless automationTypeSnapshot = HYBRID
 *   - runByUserId is null for automated runs
 *
 * ── EVIDENCE LINKAGE ─────────────────────────────────────────────────────────
 * controlTagSnapshot drives EvidenceReuseEngine auto-linking.
 * When evidence is uploaded with tag 'MFA':
 *   → All AuditTestInstance rows with controlTagSnapshot='MFA' get auto-linked
 *   → AuditTestEvidenceMatcher handles this (implements EvidenceTagMatcher)
 */
@Entity
@Table(name = "audit_test_instances",
    indexes = {
        @Index(name = "idx_ati_engagement",  columnList = "engagement_id"),
        @Index(name = "idx_ati_tenant",      columnList = "tenant_id"),
        @Index(name = "idx_ati_original",    columnList = "original_test_id"),
        @Index(name = "idx_ati_result",      columnList = "test_result"),
        @Index(name = "idx_ati_tag",         columnList = "control_tag_snapshot")
    }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditTestInstance extends TenantAwareEntity {

    // ── Scope ─────────────────────────────────────────────────────────────────

    /** Plain Long — audit trail only. NEVER used in runtime joins. */
    @Column(name = "original_test_id", nullable = false)
    private Long originalTestId;

    @Column(name = "engagement_id", nullable = false)
    private Long engagementId;

    // ── Snapshots (frozen at instantiation) ───────────────────────────────────

    @Column(name = "test_name_snapshot", nullable = false, length = 500)
    private String testNameSnapshot;

    @Column(name = "test_ref_snapshot", length = 30)
    private String testRefSnapshot;

    @Column(name = "description_snapshot", columnDefinition = "TEXT")
    private String descriptionSnapshot;

    @Column(name = "test_procedure_snapshot", columnDefinition = "TEXT")
    private String testProcedureSnapshot;

    @Column(name = "evidence_guidance_snapshot", columnDefinition = "TEXT")
    private String evidenceGuidanceSnapshot;

    @Column(name = "framework_ref_snapshot", length = 100)
    private String frameworkRefSnapshot;

    /**
     * Snapshotted tag for EvidenceReuseEngine matching.
     * AuditTestEvidenceMatcher uses this field.
     */
    @Column(name = "control_tag_snapshot", length = 80)
    private String controlTagSnapshot;

    @Column(name = "automation_type_snapshot", length = 20)
    private String automationTypeSnapshot;   // "AUTOMATED" | "MANUAL" | "HYBRID"

    @Column(name = "automation_key_snapshot", length = 100)
    private String automationKeySnapshot;

    @Column(name = "frequency_snapshot", length = 20)
    private String frequencySnapshot;

    @Column(name = "snapshotted_at", nullable = false)
    private LocalDateTime snapshottedAt;

    // ── Test execution ────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "test_result", nullable = false, length = 20)
    @Builder.Default
    private TestResult testResult = TestResult.NOT_RUN;

    @Column(name = "run_at")
    private LocalDateTime runAt;

    /** null for automated runs; user ID for manual runs */
    @Column(name = "run_by_user_id")
    private Long runByUserId;

    /** true when result was set by KashiGuard automation */
    @Column(name = "run_by_system", nullable = false)
    @Builder.Default
    private boolean runBySystem = false;

    @Column(name = "tester_notes", columnDefinition = "TEXT")
    private String testerNotes;

    /** For FAIL results: what specifically failed */
    @Column(name = "failure_detail", columnDefinition = "TEXT")
    private String failureDetail;

    /** For EXCEPTION results: why the test could not be run */
    @Column(name = "exception_reason", columnDefinition = "TEXT")
    private String exceptionReason;

    // ── KashiGuard automation result (for AUTOMATED/HYBRID) ──────────────────

    /** Raw result from KashiGuard check — e.g. "MFA disabled for 3 users: alice, bob, carol" */
    @Column(name = "automation_raw_result", columnDefinition = "TEXT")
    private String automationRawResult;

    @Column(name = "automation_run_at")
    private LocalDateTime automationRunAt;

    // ── Derived control impact ────────────────────────────────────────────────

    /**
     * How many AuditControlInstance rows changed result because of this test's last run.
     * Denormalized for display — "This test affected 3 controls."
     * Updated by AuditEngagementService.deriveControlResults() after each result change.
     */
    @Column(name = "affected_control_count")
    @Builder.Default
    private Integer affectedControlCount = 0;

    public enum TestResult {
        NOT_RUN,
        PASS,
        FAIL,
        EXCEPTION
    }
}
