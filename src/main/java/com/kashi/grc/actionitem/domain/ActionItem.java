package com.kashi.grc.actionitem.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * Runtime instance of an action obligation.
 *
 * Always tenant-scoped. Never global.
 *
 * ── NEW FIELDS ─────────────────────────────────────────────────────────────────
 *
 * itemScreenKey — screen config key for rendering the action item work UI.
 *   When an assignee opens this action item, they need to see the item they're
 *   working on with a UI appropriate to the work type.
 *   GET /v1/ui-config/screen/:itemScreenKey returns fields, actions, ItemPanel config.
 *   Null = generic action item view (title, description, resolve button only).
 *   Examples:
 *     "risk_control_item"       — control evidence form (CONTROL entity)
 *     "audit_finding_item"      — finding detail with evaluate/accept buttons
 *     "vendor_fill_question"    — question response card (TPRM, backward compat)
 *
 * itemUiJson — inline UI override for the item work screen.
 *   Applied on top of itemScreenKey config. Same pattern as WorkflowStepSection.itemUiJson.
 *   { "editableFields": [...], "showEvidence": true, "showComments": true, "itemPanelMode": "responder" }
 *   Null = all defaults from itemScreenKey apply.
 *
 * parentEntityType / parentEntityId — the parent record context.
 *   entityType + entityId = the specific item being delegated (CONTROL 42, FINDING 99)
 *   parentEntityType + parentEntityId = the parent record (RISK 10, AUDIT 5)
 *   Used for:
 *     - "Back to [risk]" navigation in action item work screen
 *     - Breadcrumb: Risk #10 > Control #42 > Action item
 *     - Invalidating parent page queries after action item resolution
 *   Null for global action items (no parent context).
 *
 * MIGRATION:
 *   ALTER TABLE action_items
 *     ADD COLUMN item_screen_key    VARCHAR(100) NULL
 *       COMMENT 'Screen config key for item work UI',
 *     ADD COLUMN item_ui_json       JSON NULL
 *       COMMENT 'Inline UI override for item work screen',
 *     ADD COLUMN parent_entity_type VARCHAR(30) NULL
 *       COMMENT 'Parent record entity type (e.g. RISK, AUDIT)',
 *     ADD COLUMN parent_entity_id   BIGINT NULL
 *       COMMENT 'Parent record entity ID';
 */
@Entity
@Table(name = "action_items", indexes = {
        @Index(name = "idx_ai_assigned",      columnList = "assigned_to,status"),
        @Index(name = "idx_ai_resolution",    columnList = "resolution_reserved_for,status"),
        @Index(name = "idx_ai_source",        columnList = "source_type,source_id"),
        @Index(name = "idx_ai_entity",        columnList = "entity_type,entity_id"),
        @Index(name = "idx_ai_tenant_status", columnList = "tenant_id,status"),
        @Index(name = "idx_ai_blueprint",     columnList = "blueprint_id"),
        @Index(name = "idx_ai_vendor",        columnList = "vendor_id"),
        @Index(name = "idx_ai_parent",        columnList = "parent_entity_type,parent_entity_id"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ActionItem extends TenantAwareEntity {

    // ── Blueprint link ─────────────────────────────────────────────────────
    @Column(name = "blueprint_id")
    private Long blueprintId;

    // ── Assignment ─────────────────────────────────────────────────────────
    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "assigned_group_role", length = 60)
    private String assignedGroupRole;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /**
     * Vendor scope for role-based assignment.
     * Prevents cross-vendor leaks when assignedGroupRole is used.
     * Set only for TPRM items. Null for org-internal items.
     */
    @Column(name = "vendor_id")
    private Long vendorId;

    // ── What triggered this ────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private SourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    // ── What entity this is about ──────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 30)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    // ── NEW: Parent context ────────────────────────────────────────────────
    /**
     * Parent record entity type. The record that contains the entity being worked on.
     * entityType=CONTROL, entityId=42, parentEntityType=RISK, parentEntityId=10
     * → "Working on Control #42 within Risk #10"
     *
     * Used for breadcrumb navigation and parent page query invalidation.
     * Null for top-level action items (no parent context).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "parent_entity_type", length = 30)
    private EntityType parentEntityType;

    @Column(name = "parent_entity_id")
    private Long parentEntityId;

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
    @Column(name = "resolution_reserved_for")
    private Long resolutionReservedFor;

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
     * Tells the assignee exactly where to navigate to do the work.
     *
     * For TPRM (existing, unchanged):
     * { "route": "/vendor/assessments/23/fill",
     *   "questionInstanceId": 1239, "sectionInstanceId": 45, "assessmentId": 23 }
     *
     * For new modules (Universal Module Page):
     * { "route": "/module/risk/10",
     *   "tab": "evidence",
     *   "sectionKey": "control_assessment",
     *   "itemId": 88,
     *   "itemRefType": "CONTROL",
     *   "itemRefId": 42 }
     */
    @Column(name = "nav_context", columnDefinition = "JSON")
    private String navContext;

    // ── NEW: Item UI rendering ─────────────────────────────────────────────
    /**
     * Screen config key for the item work UI.
     * When assignee opens this action item, frontend fetches:
     *   GET /v1/ui-config/screen/:itemScreenKey
     * Returns fields, actions, and ItemPanel config for the item.
     *
     * Null = generic action item view (title, description, resolve button).
     *
     * Examples:
     *   "risk_control_item"    — control evidence form
     *   "audit_finding_item"   — finding detail with evaluate/accept
     *   "vendor_fill_question" — question response card (TPRM)
     */
    @Column(name = "item_screen_key", length = 100)
    private String itemScreenKey;

    /**
     * Inline UI override for the item work screen.
     * Applied on top of itemScreenKey defaults.
     * Same pattern as WorkflowStepSection.itemUiJson.
     *
     * { "editableFields": ["evidenceText", "complianceStatus"],
     *   "readOnlyFields": ["controlCode"],
     *   "showEvidence":   true,
     *   "showComments":   true,
     *   "showActionItems": true,
     *   "itemPanelMode":  "responder" }
     */
    @Column(name = "item_ui_json", columnDefinition = "JSON")
    private String itemUiJson;

    // ── Remediation tracking ───────────────────────────────────────────────
    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "expected_evidence", columnDefinition = "TEXT")
    private String expectedEvidence;

    @Column(name = "remediation_type", length = 30)
    private String remediationType;

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
        OPEN,
        IN_PROGRESS,
        PENDING_REVIEW,
        PENDING_VALIDATION,
        SUBMITTED,
        RESOLVED,
        DISMISSED
    }

    public enum Priority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public enum SourceType {
        COMMENT,           // from REVISION_REQUEST or REMEDIATION comment
        AUDIT_FINDING,     // from audit finding
        CONTROL_GAP,       // from control gap assessment
        RISK_ESCALATION,   // from risk management
        ISSUE,             // from issue management
        SYSTEM,            // auto-created by system (or direct actor delegation)
        WORKFLOW_STEP,     // created directly from a compound task section item
        POLICY_REVIEW,     // from policy review cycle
        INCIDENT_REPORT    // from incident management
    }

    public enum EntityType {
        // Existing
        QUESTION_RESPONSE,
        ASSESSMENT,
        VENDOR,
        TASK,
        CONTROL,
        RISK,
        AUDIT,
        FINDING,
        ISSUE,
        // New modules
        POLICY,
        POLICY_CLAUSE,
        RISK_CONTROL,
        AUDIT_EVIDENCE,
        RISK_GAP,
        EXCEPTION,
        INCIDENT
    }
}