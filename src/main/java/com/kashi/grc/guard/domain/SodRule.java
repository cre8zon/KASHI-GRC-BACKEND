package com.kashi.grc.guard.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import com.kashi.grc.usermanagement.domain.Role;
import jakarta.persistence.*;
import lombok.*;

/**
 * Unified Segregation of Duties rule — single source of truth for ALL SoD constraints.
 * Table: sod_rules
 *
 * ── RULE TYPES ────────────────────────────────────────────────────────────────
 *
 * ROLE_PAIR (was usermanagement.SodRule):
 *   Two roles that cannot be assigned to the same user simultaneously.
 *   Evaluated by RoleServiceImpl.assignRoleToUser() at role assignment time.
 *   Uses: conflictingRole1, conflictingRole2, conflictType, description, tenantId.
 *   Example: "RISK_CREATOR and RISK_APPROVER cannot be held by the same user."
 *
 * PERMISSION_PAIR (was guard.SodRule on permission_sod_rules):
 *   Two permissions that cannot be exercised by the same user on the same workflow
 *   instance (same entity record). Evaluated by WorkflowAccessService.evaluateSod()
 *   at access resolution time.
 *   Uses: permissionA, permissionB, conflictType, scope, entityTypes, tenantId.
 *   Example: "risk.create and risk.approve cannot both be performed on Risk #42."
 *
 * ── WHY UNIFIED ───────────────────────────────────────────────────────────────
 *
 * SoD is a single GRC concept. Both rule types represent "the same person cannot
 * do X and Y." Platform Admin configures all SoD rules in one place (/admin/rbac/sod-rules).
 * The enforcement point (role assignment vs. access resolution) is an implementation
 * detail hidden from the admin.
 *
 * ── MIGRATION SQL ─────────────────────────────────────────────────────────────
 *
 * Run ONCE before deploying to add new columns and migrate existing ROLE_PAIR data:
 *
 *   ALTER TABLE sod_rules
 *     ADD COLUMN rule_type     VARCHAR(20)  NOT NULL DEFAULT 'ROLE_PAIR',
 *     ADD COLUMN rule_name     VARCHAR(255),
 *     ADD COLUMN permission_a  VARCHAR(100),
 *     ADD COLUMN permission_b  VARCHAR(100),
 *     ADD COLUMN conflict_type VARCHAR(10),
 *     ADD COLUMN scope         VARCHAR(20),
 *     ADD COLUMN entity_types  VARCHAR(255),
 *     ADD COLUMN framework_ref VARCHAR(100),
 *     ADD COLUMN is_active     TINYINT(1)   NOT NULL DEFAULT 1,
 *     MODIFY COLUMN conflicting_role1_id BIGINT NULL,
 *     MODIFY COLUMN conflicting_role2_id BIGINT NULL;
 *
 *   -- Migrate enforcement_mode → conflict_type for existing ROLE_PAIR records
 *   UPDATE sod_rules SET conflict_type = 'HARD'
 *     WHERE enforcement_mode = 'HARD_BLOCK' AND conflict_type IS NULL;
 *   UPDATE sod_rules SET conflict_type = 'SOFT'
 *     WHERE enforcement_mode IS NOT NULL AND enforcement_mode != 'HARD_BLOCK'
 *       AND conflict_type IS NULL;
 *   -- Set default rule_name from description for existing records
 *   UPDATE sod_rules SET rule_name = LEFT(description, 255)
 *     WHERE rule_name IS NULL AND description IS NOT NULL;
 */
@Entity
@Table(name = "sod_rules")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class SodRule extends GlobalOrTenantEntity {

    // ── Rule type discriminator ──────────────────────────────────────────────

    /**
     * Determines which fields are populated and which enforcement point is used.
     * ROLE_PAIR       → evaluated by RoleServiceImpl at role assignment time
     * PERMISSION_PAIR → evaluated by WorkflowAccessService at access resolution time
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 20)
    @Builder.Default
    private RuleType ruleType = RuleType.PERMISSION_PAIR;

    // ── Common fields ────────────────────────────────────────────────────────

    /** Human-readable rule name shown in the admin UI. */
    @Column(name = "rule_name", length = 255)
    private String ruleName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Whether this rule is currently enforced.
     * Soft-delete pattern — inactive rules are ignored at evaluation time.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    /**
     * HARD = block the action entirely (role assignment blocked / access denied).
     * SOFT = allow with a SodViolationRecord that requires documented justification.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_type", length = 10)
    @Builder.Default
    private ConflictType conflictType = ConflictType.HARD;

    // tenantId is inherited from GlobalOrTenantEntity:
    //   NULL  = global platform rule (visible to all tenants)
    //   non-null = tenant-specific rule

    // ── ROLE_PAIR fields — null for PERMISSION_PAIR rules ────────────────────

    /**
     * First conflicting role.
     * Nullable: PERMISSION_PAIR rules do not use role references.
     */
    @Column(name = "conflicting_role1_id", insertable = false, updatable = false)
    private Long conflictingRole1Id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "conflicting_role1_id")
    private Role conflictingRole1;

    /**
     * Second conflicting role.
     * Nullable: PERMISSION_PAIR rules do not use role references.
     */
    @Column(name = "conflicting_role2_id", insertable = false, updatable = false)
    private Long conflictingRole2Id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "conflicting_role2_id")
    private Role conflictingRole2;

    /**
     * Legacy context type (ROLE_PAIR only).
     * e.g. "WORKFLOW", "ASSESSMENT", "GLOBAL" — the domain where the role conflict applies.
     */
    @Column(name = "context_type")
    private String contextType;

    /**
     * Legacy severity string (ROLE_PAIR only).
     * Kept for backward compatibility with existing records and SodCheckResponse DTOs.
     * New code should use conflictType instead.
     */
    @Column(name = "severity")
    @Builder.Default
    private String severity = "HIGH";

    /**
     * Legacy enforcement mode string (ROLE_PAIR only).
     * Kept for backward compatibility with existing records.
     * New code should use conflictType instead — migrated via the SQL above.
     */
    @Column(name = "enforcement_mode")
    private String enforcementMode;

    // ── PERMISSION_PAIR fields — null for ROLE_PAIR rules ───────────────────

    /**
     * First conflicting permission key.
     * e.g. "risk.create" — must match Permission.code exactly.
     */
    @Column(name = "permission_a", length = 100)
    private String permissionA;

    /**
     * Second conflicting permission key.
     * e.g. "risk.approve"
     */
    @Column(name = "permission_b", length = 100)
    private String permissionB;

    /**
     * Scope of PERMISSION_PAIR evaluation.
     * INSTANCE = conflict only within the same workflow instance (most common GRC use case).
     * GLOBAL   = user cannot hold both permissions anywhere on the platform.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", length = 20)
    @Builder.Default
    private SodScope scope = SodScope.INSTANCE;

    /**
     * Which entity types this PERMISSION_PAIR rule applies to.
     * NULL = all entity types. Comma-separated for multiple: "RISK,AUDIT"
     */
    @Column(name = "entity_types", length = 255)
    private String entityTypes;

    /**
     * Compliance framework that mandates this rule.
     * e.g. "SOX", "PCI-DSS", "ISO27001", "SOC2"
     */
    @Column(name = "framework_ref", length = 100)
    private String frameworkRef;

    // ── Enums ────────────────────────────────────────────────────────────────

    public enum RuleType {
        /** Two roles that cannot be assigned to the same user simultaneously. */
        ROLE_PAIR,
        /** Two permissions that cannot be exercised on the same workflow instance. */
        PERMISSION_PAIR
    }

    public enum ConflictType {
        /** Block the role assignment or workflow action entirely. */
        HARD,
        /** Allow with a SodViolationRecord requiring documented justification. */
        SOFT
    }

    public enum SodScope {
        /** Conflict only within the same workflow instance (same entity record). */
        INSTANCE,
        /** User cannot hold both permissions anywhere on the platform. */
        GLOBAL
    }
}