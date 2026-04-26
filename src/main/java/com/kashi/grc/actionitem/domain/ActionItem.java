package com.kashi.grc.actionitem.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Runtime instance of an action obligation.
 *
 * Always tenant-scoped (extends TenantAwareEntity).
 * Never global — instances are runtime data belonging to a specific org.
 *
 * blueprint_id is nullable:
 *   null     = ad-hoc (REVISION_REQUEST, one-off escalation)
 *   non-null = instantiated from a standard blueprint (audit finding, control gap)
 *
 * Resolution rules (enforced by ActionItemService):
 *   OPEN → IN_PROGRESS   : assigned_to ("I'm working on it")
 *   OPEN → DISMISSED     : assigned_to or ORG_ADMIN
 *   * → RESOLVED         : resolution_reserved_for (if set) OR any user with resolutionRole
 *   RESOLVED → OPEN      : same as RESOLVED (re-open if not satisfied)
 */
@Entity
@Table(name = "action_items", indexes = {
        @Index(name = "idx_ai_assigned",      columnList = "assigned_to,status"),
        @Index(name = "idx_ai_resolution",    columnList = "resolution_reserved_for,status"),
        @Index(name = "idx_ai_source",        columnList = "source_type,source_id"),
        @Index(name = "idx_ai_entity",        columnList = "entity_type,entity_id"),
        @Index(name = "idx_ai_tenant_status", columnList = "tenant_id,status"),
        @Index(name = "idx_ai_blueprint",     columnList = "blueprint_id"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ActionItem extends TenantAwareEntity {

    // ── Blueprint link ─────────────────────────────────────────────────────
    /** null for ad-hoc items; set for standard finding instances */
    @Column(name = "blueprint_id")
    private Long blueprintId;

    // ── Assignment ─────────────────────────────────────────────────────────
    /** Specific user who must act. Nullable if assigned to a group role. */
    @Column(name = "assigned_to")
    private Long assignedTo;

    /**
     * Role-based assignment fallback.
     * Any user with this role on the entity's workflow can claim and act.
     * e.g. 'VENDOR_RESPONDER', 'VENDOR_CISO', 'ORG_REVIEWER'
     */
    @Column(name = "assigned_group_role", length = 60)
    private String assignedGroupRole;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    // ── What triggered this ────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private SourceType sourceType;

    /** ID of the triggering record — e.g. comment.id, finding.id */
    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    // ── What entity this is about ──────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    // ── Content ────────────────────────────────────────────────────────────
    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ── State ──────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    @Builder.Default
    private Priority priority = Priority.MEDIUM;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    // ── Resolution ─────────────────────────────────────────────────────────
    /**
     * Specific user who can mark RESOLVED.
     * Takes precedence over resolutionRole when set.
     * Used when a specific person must personally sign off (e.g. lead auditor).
     */
    @Column(name = "resolution_reserved_for")
    private Long resolutionReservedFor;

    /**
     * Role-based resolution.
     * Any user with this role can mark RESOLVED.
     * e.g. 'VENDOR_RESPONDER', 'ORG_REVIEWER', 'LEAD_AUDITOR'
     * Used when any team member of the right role can accept the fix.
     */
    @Column(name = "resolution_role", length = 60)
    private String resolutionRole;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    // ── Navigation context ─────────────────────────────────────────────────
    /**
     * JSON deep-link context for frontend routing.
     * Tells the frontend exactly where to navigate for this action item.
     * Example:
     * {
     *   "route": "/vendor/assessments/23/fill",
     *   "questionInstanceId": 1239,
     *   "sectionInstanceId": 45,
     *   "assessmentId": 23
     * }
     * The action items system is agnostic to module-specific routing.
     * Each module populates this at creation time.
     */
    @Column(name = "nav_context", columnDefinition = "JSON")
    private String navContext;

    // Remediation tracking fields (nullable — only set for CLARIFICATION/REMEDIATION_REQUEST items)
    @Column(name = "severity", length = 20)
    private String severity;                      // LOW | MEDIUM | HIGH | CRITICAL

    @Column(name = "expected_evidence", columnDefinition = "TEXT")
    private String expectedEvidence;              // what resolves this item

    @Column(name = "remediation_type", length = 30)
    private String remediationType;               // CLARIFICATION | REMEDIATION_REQUEST

    @Column(name = "accepted_risk")
    @Builder.Default
    private Boolean acceptedRisk = false;

    @Column(name = "accepted_risk_by")
    private Long acceptedRiskBy;

    @Column(name = "accepted_risk_at")
    private LocalDateTime acceptedRiskAt;

    @Column(name = "accepted_risk_note", columnDefinition = "TEXT")
    private String acceptedRiskNote;

    // ── Enums ──────────────────────────────────────────────────────────────

    public enum Status {
        OPEN,           // created, assigned — in assignee's court
        IN_PROGRESS,    // assignee has started work
        PENDING_REVIEW,
        PENDING_VALIDATION,
        SUBMITTED,      // assignee done — in reviewer's court ("pending review")
        RESOLVED,       // reviewer accepted
        DISMISSED       // dropped without resolution
    }

    public enum Priority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public enum SourceType {
        COMMENT,          // created from a REVISION_REQUEST or REMEDIATION comment
        AUDIT_FINDING,    // created from an audit finding
        CONTROL_GAP,      // created from a control gap assessment
        RISK_ESCALATION,  // created from a risk management escalation
        ISSUE,            // created from the issue management module
        SYSTEM            // auto-created by the system
    }

    public enum EntityType {
        QUESTION_RESPONSE,
        ASSESSMENT,
        VENDOR,
        TASK,
        CONTROL,
        RISK,
        AUDIT,
        FINDING,
        ISSUE
    }
}