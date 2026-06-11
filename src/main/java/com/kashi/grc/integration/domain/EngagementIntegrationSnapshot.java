package com.kashi.grc.integration.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * EngagementIntegrationSnapshot — engagement-scoped instance of a global integration check.
 *
 * ── WHY THIS EXISTS ────────────────────────────────────────────────────────────
 * IntegrationCheckConfig (integration_checks table) is a global/tenant library of
 * check definitions. It tells you WHAT checks exist and HOW to run them.
 *
 * But during a SOC2 audit engagement, you need to know:
 *   - Which checks were active FOR THIS ENGAGEMENT (scoped at snapshot time)
 *   - Which specific AuditTestInstance each check result feeds into
 *   - Whether that check PASSED or FAILED within the audit window
 *   - When it last ran during this engagement's period
 *
 * Without this table, EvidenceReuseEngine propagates check results to ALL test
 * instances across ALL engagements that share the same controlTagSnapshot — which
 * means an Okta MFA check result could pollute an AWS MFA test instance in a
 * completely different engagement. This is wrong.
 *
 * ── HOW IT WORKS ──────────────────────────────────────────────────────────────
 * 1. At engagement creation (AuditTestPolicySnapshotService.snapshotTestsAndPolicies),
 *    for every AUTOMATED AuditTestInstance created, we find the matching
 *    IntegrationCheckConfig by checkKey (= automationKeySnapshot) and create one
 *    EngagementIntegrationSnapshot row linking them.
 *
 * 2. When IntegrationRunner runs a check, it calls
 *    EngagementIntegrationSnapshotService.recordResult(checkKey, tenantId, runResult)
 *    which updates all active snapshots for that check + tenant with the latest
 *    result and propagates the testResult ONLY to the linked AuditTestInstance —
 *    NOT to every instance that shares the controlTag.
 *
 * 3. The AuditTestEvidenceMatcher is updated to use automationKeySnapshot for
 *    AUTOMATED tests and fall back to controlTagSnapshot only for MANUAL tests,
 *    preventing cross-contamination.
 *
 * ── UNIQUE CONSTRAINT ─────────────────────────────────────────────────────────
 * (engagement_id, test_instance_id, check_key) — one snapshot per test per check
 * per engagement. If the same check runs multiple tests in one engagement (unusual
 * but possible), each gets its own snapshot row.
 */
@Entity
@Table(
        name = "engagement_integration_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_eng_test_check",
                columnNames = {"engagement_id", "test_instance_id", "check_key"}
        ),
        indexes = {
                @Index(name = "idx_eis_engagement",   columnList = "engagement_id"),
                @Index(name = "idx_eis_tenant",       columnList = "tenant_id"),
                @Index(name = "idx_eis_check_key",    columnList = "check_key"),
                @Index(name = "idx_eis_test_inst",    columnList = "test_instance_id"),
                @Index(name = "idx_eis_active",       columnList = "is_active")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class EngagementIntegrationSnapshot extends TenantAwareEntity {

    // ── Engagement context ────────────────────────────────────────────────────

    /** The audit engagement this snapshot belongs to. */
    @Column(name = "engagement_id", nullable = false)
    private Long engagementId;

    // ── Test linkage ──────────────────────────────────────────────────────────

    /**
     * The specific AuditTestInstance this check result feeds into.
     * This is the precise target — NOT "all tests with the same tag".
     */
    @Column(name = "test_instance_id", nullable = false)
    private Long testInstanceId;

    /**
     * Original AuditTest.automationKey, snapshotted at engagement creation.
     * Matches IntegrationCheckConfig.checkKey (e.g. "OKTA_ADMIN_MFA").
     * Used by IntegrationRunner to find the right snapshot when a check completes.
     */
    @Column(name = "check_key", nullable = false, length = 100)
    private String checkKey;

    /**
     * Snapshotted from IntegrationCheckConfig.integrationKey (e.g. "OKTA", "AWS").
     * Stored here so queries don't need to join integration_checks.
     */
    @Column(name = "integration_key", nullable = false, length = 50)
    private String integrationKey;

    /**
     * Snapshotted from AuditTestInstance.controlTagSnapshot.
     * Kept for debugging/display — NOT used for matching (checkKey is used instead).
     */
    @Column(name = "control_tag_snapshot", nullable = false, length = 80)
    private String controlTagSnapshot;

    /**
     * Human-readable display name snapshotted from IntegrationCheckConfig.displayName.
     * Shown in the engagement dashboard without needing to join back to the library.
     */
    @Column(name = "display_name_snapshot", length = 300)
    private String displayNameSnapshot;

    // ── Run frequency snapshot ────────────────────────────────────────────────

    /**
     * Snapshotted from IntegrationCheckConfig.runFrequency at engagement creation.
     * Used to show expected cadence in the UI without joining to library tables.
     * HOURLY | DAILY | WEEKLY | MONTHLY
     */
    @Column(name = "run_frequency_snapshot", length = 10)
    private String runFrequencySnapshot;

    // ── Active flag ───────────────────────────────────────────────────────────

    /**
     * Whether this snapshot is still active (i.e. the engagement is ongoing).
     * Set to false when the engagement is CLOSED or CANCELLED so the IntegrationRunner
     * stops feeding results into completed engagements.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    // ── Latest run state ──────────────────────────────────────────────────────

    /**
     * Result of the most recent check run during this engagement's audit window.
     * PASS | FAIL | ERROR | NOT_RUN
     * NOT_RUN = check has never executed since engagement was created.
     */
    @Column(name = "last_result", length = 10)
    @Builder.Default
    private String lastResult = "NOT_RUN";

    /** Summary message from the most recent run (passed to AuditTestInstance.testerNotes). */
    @Column(name = "last_result_summary", columnDefinition = "TEXT")
    private String lastResultSummary;

    /** Timestamp of the most recent run that targeted this snapshot. */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** EvidenceRecord created by the most recent run — for linking to the test instance. */
    @Column(name = "last_evidence_record_id")
    private Long lastEvidenceRecordId;

    /** IntegrationRun id of the most recent run for traceability. */
    @Column(name = "last_integration_run_id")
    private Long lastIntegrationRunId;

    /**
     * How many times this check has run during the engagement window.
     * Useful for showing audit frequency compliance on the engagement dashboard.
     */
    @Column(name = "run_count", nullable = false)
    @Builder.Default
    private int runCount = 0;

    // ── Snapshot metadata ─────────────────────────────────────────────────────

    /** When this snapshot was created (= engagement creation time). */
    @Column(name = "snapshotted_at", nullable = false)
    private LocalDateTime snapshottedAt;

    /** IntegrationCheckConfig.id at snapshot time — for audit trail only. */
    @Column(name = "original_check_config_id")
    private Long originalCheckConfigId;
}