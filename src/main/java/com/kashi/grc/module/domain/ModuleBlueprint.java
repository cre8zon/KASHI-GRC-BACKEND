package com.kashi.grc.module.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * ModuleBlueprint — the "data shape" layer for any GRC entity type.
 *
 * Each row defines a complete GRC module (RISK, AUDIT, ISSUE, POLICY, CONTROL, etc.)
 * that the Universal Module Page can render WITHOUT any code deployment.
 *
 * ── NEW FIELDS (v2) ──────────────────────────────────────────────────────────
 *
 *  parentContextJson   — lets a child module (e.g. AUDIT_CONTROL_INSTANCE) declare
 *                        that it is always scoped under a parent entity. The Universal
 *                        Module Page reads this to resolve the API path and route params.
 *                        Format (JSON):
 *                        {
 *                          "parentEntityType": "AUDIT_ENGAGEMENT",
 *                          "parentIdParam":    "engagementId",
 *                          "apiBasePath":      "/v1/audit/engagements/{engagementId}/controls"
 *                        }
 *                        Route becomes: /module/:parentEntityType/:parentId/:entityType[/:id]
 *
 *  supportsTree        — when true, the list view renders a recursive tree (using
 *                        EntityTreeView component) instead of a flat DataTable.
 *                        Expects the API to return items with { id, parentId, name, ... }.
 *                        The tree renderer is a fixed component — this flag just toggles it.
 *
 *  wsTopicPattern      — STOMP topic pattern for live updates. Supports {id} placeholder.
 *                        e.g. "/topic/module/audit_engagement/{id}"
 *                        When set and the user is on the detail page, UniversalModulePage
 *                        subscribes to this topic and auto-invalidates React Query caches
 *                        on any incoming event. Zero code per module — just set the pattern.
 *                        The backend must publish to this topic on mutations (Spring's
 *                        SimpMessagingTemplate). Configure the topic name here; configure
 *                        the backend publisher separately per module.
 */
@Entity
@Table(
        name = "module_blueprints",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_module_entity_type",
                columnNames = {"entity_type", "tenant_id"}
        )
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class ModuleBlueprint extends GlobalOrTenantEntity {

    /**
     * The entity type key — matches WorkflowInstance.entityType exactly.
     * e.g. "RISK", "AUDIT_ENGAGEMENT", "ISSUE", "AUDIT_CONTROL_INSTANCE"
     * Must be UPPER_SNAKE_CASE, unique per tenant.
     */
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    /** Display name shown in UI. e.g. "Audit Engagement" */
    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    /** Plural display name. e.g. "Audit Engagements" */
    @Column(name = "display_name_plural", length = 255)
    private String displayNamePlural;

    /** Lucide icon name. e.g. "ClipboardCheck", "ShieldAlert" */
    @Column(name = "icon", length = 100)
    private String icon;

    /** Semantic color for badges. e.g. "indigo", "blue", "amber" */
    @Column(name = "color_tag", length = 30)
    @Builder.Default
    private String colorTag = "blue";

    /**
     * JSON Schema for the entity's fields.
     * Used to build the overview panel and fallback list columns.
     */
    @Column(name = "fields_schema_json", columnDefinition = "JSON")
    private String fieldsSchemaJson;

    /**
     * Valid status transitions as JSON.
     * { "statuses": [...], "transitions": [{ "from", "to", "label", "permission" }] }
     */
    @Column(name = "status_flow_json", columnDefinition = "JSON")
    private String statusFlowJson;

    /**
     * Comma-separated workflow entityType values this module is eligible for.
     * e.g. "RISK,RISK_ACCEPTANCE"
     */
    @Column(name = "workflow_eligibility", length = 500)
    private String workflowEligibility;

    /** Screen config key for the LIST view. */
    @Column(name = "list_screen_key", length = 100)
    private String listScreenKey;

    /** Screen config key for the DETAIL view. */
    @Column(name = "detail_screen_key", length = 100)
    private String detailScreenKey;

    /** Form key for the CREATE form. */
    @Column(name = "create_form_key", length = 100)
    private String createFormKey;

    /** Form key for the EDIT form. Falls back to createFormKey when null. */
    @Column(name = "edit_form_key", length = 100)
    private String editFormKey;

    /** Base API endpoint for CRUD. e.g. "/v1/risks" or "/v1/audit/engagements" */
    @Column(name = "api_base_path", nullable = false, length = 255)
    private String apiBasePath;

    // ── Capabilities ─────────────────────────────────────────────────────────

    @Column(name = "supports_action_items", nullable = false)
    @Builder.Default
    private boolean supportsActionItems = false;  // opt-in: enable in Blueprint Settings

    @Column(name = "supports_documents", nullable = false)
    @Builder.Default
    private boolean supportsDocuments = false;  // opt-in: enable in Blueprint Settings

    @Column(name = "supports_comments", nullable = false)
    @Builder.Default
    private boolean supportsComments = false;  // opt-in: enable in Blueprint Settings

    @Column(name = "supports_history", nullable = false)
    @Builder.Default
    private boolean supportsHistory = true;  // audit trail — on by default, toggleable in Blueprint Settings

    @Column(name = "supports_workflow", nullable = false)
    @Builder.Default
    private boolean supportsWorkflow = false;  // opt-in: enable in Blueprint Settings

    @Column(name = "show_in_nav", nullable = false)
    @Builder.Default
    private boolean showInNav = true;

    /**
     * [NEW v2] When true, the list view renders a recursive tree (EntityTreeView)
     * instead of a flat DataTable. Expects API items to have { id, parentId, ... }.
     * Configure in Module Blueprints UI — zero code per module.
     */
    @Column(name = "supports_tree", nullable = false)
    @Builder.Default
    private boolean supportsTree = false;

    /**
     * [NEW v2] JSON config for child-scoped modules.
     *
     * When set, this module's records always belong to a parent entity.
     * The Universal Module Page reads this to:
     *   1. Resolve the API path — substituting {parentIdParam} with the URL param
     *   2. Add parent breadcrumb in the list view header
     *   3. Pass parentId to create forms as a hidden field
     *
     * Format:
     * {
     *   "parentEntityType": "AUDIT_ENGAGEMENT",
     *   "parentIdParam":    "engagementId",
     *   "apiBasePath":      "/v1/audit/engagements/{engagementId}/controls",
     *   "parentLabelField": "name"
     * }
     *
     * Routes automatically become:
     *   /module/:parentEntityType/:parentId/:entityType       (list)
     *   /module/:parentEntityType/:parentId/:entityType/:id   (detail)
     *
     * parentLabelField — optional. Field name on the parent entity used to show
     *   the parent's name in the breadcrumb (e.g. "name" → "Controls under ISO 27001 Audit").
     *   Defaults to "name" if absent.
     */
    @Column(name = "parent_context_json", columnDefinition = "JSON")
    private String parentContextJson;

    /**
     * [NEW v2] STOMP WebSocket topic pattern for live updates on the detail page.
     *
     * When set, UniversalModulePage subscribes to this topic on the detail view
     * and auto-invalidates React Query caches when any event arrives.
     * The {id} placeholder is replaced with the entity ID at runtime.
     *
     * Examples:
     *   "/topic/module/audit_engagement/{id}"
     *   "/topic/module/issue/{id}"
     *   "/topic/module/risk/{id}"
     *
     * The backend must publish to this topic via SimpMessagingTemplate.
     * This field only controls the subscription — it does not create the publisher.
     * If null or empty, no WebSocket subscription is created (backwards compatible).
     */
    @Column(name = "ws_topic_pattern", length = 200)
    private String wsTopicPattern;

    // ── Access + ordering ─────────────────────────────────────────────────────

    /**
     * Comma-separated role sides that can access this module.
     * e.g. "ORGANIZATION,SYSTEM" — null = all sides.
     */
    @Column(name = "allowed_sides", length = 255)
    private String allowedSides;

    /**
     * Feature entitlement key. When set, this module's screens are blocked for
     * any tenant that does not have the feature enabled in feature_flags — the
     * by-type render endpoint returns 403 FEATURE_NOT_LICENSED and the frontend
     * redirects. NULL = no feature gate (available to all tenants). Configured
     * from the Module Blueprint admin (System only).
     */
    @Column(name = "required_feature", length = 100)
    private String requiredFeature;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    /** Sort order in navigation and module listings */
    @Column(name = "sort_order")
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * Navigation key — matches ui_navigation.nav_key exactly.
     * Used so the sidebar can highlight the active nav item when this module's
     * Universal Module Page is open.  e.g. "audit_tests", "org_audit_policies".
     * Null = no sidebar highlight (for child/scoped modules like audit controls).
     */
    @Column(name = "nav_key", length = 100)
    private String navKey;
}