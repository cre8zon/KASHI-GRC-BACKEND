package com.kashi.grc.integration.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * TenantIntegrationCheck — tenant-scoped instance of a global IntegrationCheckConfig.
 *
 * ── THE ISOLATION PROBLEM ─────────────────────────────────────────────────────
 * IntegrationCheckConfig (integration_checks table) is a global library — tenant_id
 * is nullable, and the unique constraint is (integration_key, check_key). This means
 * there is exactly ONE row per check shared by all tenants. When someone updates the
 * global library (changes passCriteria, checkConfig, frequency), it immediately
 * affects every tenant currently running an audit. This breaks the 100% isolation
 * contract that the rest of the system (AuditTest → AuditTestInstance, AuditPolicy
 * → AuditPolicyInstance) upholds.
 *
 * ── THE SOLUTION: THREE-LAYER PATTERN ────────────────────────────────────────
 *
 *   Layer 1 — GLOBAL LIBRARY (IntegrationCheckConfig, tenant_id=NULL)
 *     The canonical definition maintained by KashiGRC platform admins.
 *     Check descriptions, control tag mappings, suggested pass criteria.
 *     Read-only for tenants. Changes here do NOT affect existing tenant instances.
 *
 *   Layer 2 — TENANT INSTANCE (TenantIntegrationCheck, tenant_id=tenantId)  ← THIS CLASS
 *     Created when a tenant connects an integration (POST /v1/integrations/{key}/connect).
 *     Copies all fields from the global library at connection time (snapshot).
 *     Tenant can override: checkConfigJson, passCriteriaJson, runFrequency, displayName.
 *     IntegrationRunner reads from here, NOT from the global library.
 *     The originalCheckConfigId backlink is plain Long — no join to global table.
 *
 *   Layer 3 — ENGAGEMENT SNAPSHOT (EngagementIntegrationSnapshot, per engagement)
 *     Already built. Maps TenantIntegrationCheck to a specific AuditTestInstance
 *     within a specific engagement. Snapshotted at engagement creation so
 *     subsequent changes to the tenant instance don't affect running engagements.
 *
 * ── HOW IT FITS THE FULL PATTERN ─────────────────────────────────────────────
 *
 *   Audit module parallel:
 *     AuditTest (library) → AuditTestInstance (per engagement, snapshotted)
 *
 *   Integration module parallel:
 *     IntegrationCheckConfig (global library)
 *       → TenantIntegrationCheck (per tenant, customisable)         ← THIS CLASS
 *         → EngagementIntegrationSnapshot (per engagement, snapshotted)
 *
 * ── UNIQUE CONSTRAINT ─────────────────────────────────────────────────────────
 * (tenant_id, integration_key, check_key) — one instance per tenant per check.
 * A tenant can only have one active copy of each check; they customise it in place.
 *
 * ── WHO CREATES THESE ─────────────────────────────────────────────────────────
 * TenantIntegrationCheckService.activateForTenant(integrationKey, tenantId):
 *   Called when a tenant connects an integration. Finds all global checks for
 *   that integration_key, creates one TenantIntegrationCheck per check by
 *   snapshotting the global definition into tenant-owned rows.
 *
 * ── WHO READS THESE ───────────────────────────────────────────────────────────
 * IntegrationRunner: instead of checkConfigRepo.findByIntegrationKeyAndIsActiveTrue(),
 *   now calls tenantCheckRepo.findByIntegrationKeyAndTenantIdAndIsActiveTrue().
 * EngagementIntegrationSnapshotService: reads tenant instance to snapshot into
 *   EngagementIntegrationSnapshot at engagement creation.
 */
@Entity
@Table(
        name = "tenant_integration_checks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tenant_integration_check",
                columnNames = {"tenant_id", "integration_key", "check_key"}
        ),
        indexes = {
                @Index(name = "idx_tic_tenant",           columnList = "tenant_id"),
                @Index(name = "idx_tic_integration_key",  columnList = "integration_key"),
                @Index(name = "idx_tic_check_key",        columnList = "check_key"),
                @Index(name = "idx_tic_active",           columnList = "is_active"),
                @Index(name = "idx_tic_tenant_int_active", columnList = "tenant_id,integration_key,is_active")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class TenantIntegrationCheck extends TenantAwareEntity {

    // ── Library backlink (audit trail only — no join) ─────────────────────────

    /**
     * ID of the global IntegrationCheckConfig this was snapshotted from.
     * Plain Long — no @ManyToOne. Changes to the global library don't affect
     * this row after it is created. Null if manually created without a library source.
     */
    @Column(name = "original_check_config_id")
    private Long originalCheckConfigId;

    // ── Identity (snapshotted from global library at connection time) ─────────

    @Column(name = "integration_key", nullable = false, length = 50)
    private String integrationKey;

    @Column(name = "check_key", nullable = false, length = 100)
    private String checkKey;

    /**
     * Tenant-customisable display name. Defaults to the global library name.
     * Shown in the tenant's integration dashboard and engagement snapshot views.
     */
    @Column(name = "display_name", nullable = false, length = 300)
    private String displayName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * The control tag this check populates evidence for.
     * Snapshotted from the global library. Tenant should not change this —
     * it must match the controlTagSnapshot on the AuditTest library record
     * for EvidenceReuseEngine propagation to work correctly.
     */
    @Column(name = "control_tag", nullable = false, length = 80)
    private String controlTag;

    /** Vendor-neutral capability (e.g. MFA_ADMIN) — enables capability-based
     *  binding so a test can resolve to whichever vendor check this tenant connected. */
    @Column(name = "capability", length = 60)
    private String capability;

    // ── Tenant-overridable fields ─────────────────────────────────────────────

    /**
     * Tenant-specific check configuration. Overrides the global library default.
     * Shape varies by check — e.g. OktaAdminMfa: {"scope":"ADMINS,SUPER_ADMINS"}
     * If null, the global library's checkConfigJson is used as fallback by the runner.
     */
    @Column(name = "check_config_json", columnDefinition = "TEXT")
    private String checkConfigJson;

    /**
     * Tenant-specific pass criteria. Overrides the global library default.
     * E.g. a tenant may require 99% MFA coverage rather than 100%:
     *   {"type":"PERCENTAGE","field":"mfaEnabled","threshold":99}
     * If null, the global library's passCriteriaJson is used.
     */
    @Column(name = "pass_criteria_json", columnDefinition = "TEXT")
    private String passCriteriaJson;

    /**
     * Tenant-specific run frequency. Overrides global library default.
     * HOURLY | DAILY | WEEKLY | MONTHLY
     * A tenant may want daily checks instead of the global hourly cadence.
     */
    @Column(name = "run_frequency", nullable = false, length = 10)
    @Builder.Default
    private String runFrequency = "DAILY";

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Whether this check is active for this tenant.
     * Set to false if the tenant disables a specific check without disconnecting
     * the whole integration. IntegrationRunner skips inactive checks.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    /** When this tenant instance was created (= integration connection time). */
    @Column(name = "activated_at", nullable = false)
    private LocalDateTime activatedAt;

    /**
     * When the tenant last manually customised this check instance.
     * Null if no customisation has been made (still using snapshotted defaults).
     */
    @Column(name = "last_customised_at")
    private LocalDateTime lastCustomisedAt;

    // ── Latest run state (denormalised from IntegrationRun for fast dashboard reads) ──

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "last_run_status", length = 10)
    private String lastRunStatus; // PASS | FAIL | ERROR

    @Column(name = "last_run_summary", columnDefinition = "TEXT")
    private String lastRunSummary;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    /** How many times this check has run for this tenant total (across all engagements). */
    @Column(name = "total_run_count", nullable = false)
    @Builder.Default
    private int totalRunCount = 0;
}