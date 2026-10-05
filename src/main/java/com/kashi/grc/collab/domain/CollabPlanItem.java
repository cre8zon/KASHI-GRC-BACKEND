package com.kashi.grc.collab.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * One line of a workspace plan — a phase, a task or a milestone.
 *
 * Belongs to a programme (each programme is one timeline, e.g. one standard or
 * one subsidiary) or, with programme_id NULL, to the workspace as a whole
 * (visible to every member). An engagement's Timeline tab is the items linked
 * to that engagement plus everything under them, so there is one plan and
 * nothing to keep in sync.
 *
 * progress_mode:
 *   MANUAL — status and progress are set by the item's owner.
 *   LINKED — computed from the linked record (an audit engagement): share of
 *            controls with evidence submitted and tested; done when the
 *            engagement is closed. Owners cannot type over it.
 *
 * Every change to dates, owner or status is recorded in collab_plan_changes.
 */
@Entity
@Table(name = "collab_plan_items",
        indexes = {
                @Index(name = "idx_collab_pi_ws",     columnList = "workspace_id"),
                @Index(name = "idx_collab_pi_prog",   columnList = "programme_id"),
                @Index(name = "idx_collab_pi_owner",  columnList = "owner_user_id"),
                @Index(name = "idx_collab_pi_linked", columnList = "linked_entity_type, linked_entity_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabPlanItem extends AuditableEntity {

    public static final String KIND_PHASE     = "PHASE";
    public static final String KIND_TASK      = "TASK";
    public static final String KIND_MILESTONE = "MILESTONE";

    public static final String NOT_STARTED = "NOT_STARTED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String BLOCKED     = "BLOCKED";
    public static final String DONE        = "DONE";

    public static final String MODE_MANUAL = "MANUAL";
    public static final String MODE_LINKED = "LINKED";

    public static final String LINK_ENGAGEMENT = "AUDIT_ENGAGEMENT";

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** NULL = a workspace-level item, visible to every member. */
    @Column(name = "programme_id")
    private Long programmeId;

    @Column(name = "parent_id")
    private Long parentId;

    /** PHASE | TASK | MILESTONE */
    @Column(name = "kind", nullable = false, length = 12)
    @lombok.Builder.Default
    private String kind = KIND_TASK;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "planned_start")
    private LocalDate plannedStart;

    @Column(name = "planned_end")
    private LocalDate plannedEnd;

    @Column(name = "actual_end")
    private LocalDate actualEnd;

    /** NOT_STARTED | IN_PROGRESS | BLOCKED | DONE (computed when LINKED) */
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private String status = NOT_STARTED;

    /** 0–100, MANUAL items only. */
    @Column(name = "progress")
    @lombok.Builder.Default
    private Integer progress = 0;

    /** MANUAL | LINKED */
    @Column(name = "progress_mode", nullable = false, length = 10)
    @lombok.Builder.Default
    private String progressMode = MODE_MANUAL;

    /** AUDIT_ENGAGEMENT (more kinds later); NULL when not linked. */
    @Column(name = "linked_entity_type", length = 30)
    private String linkedEntityType;

    @Column(name = "linked_entity_id")
    private Long linkedEntityId;

    /** Comma-separated ids of items in the same workspace this one waits on. */
    @Column(name = "depends_on", length = 1000)
    private String dependsOn;

    @Column(name = "sort_order")
    @lombok.Builder.Default
    private Integer sortOrder = 0;

    /** Values of the workspace's own plan columns, {columnKey: value} as JSON (CollabPlanColumnsService). */
    @Column(name = "custom_json", columnDefinition = "TEXT")
    private String customJson;

    /**
     * How the row looks in the sheet: {"row": style, "cells": {columnKey: style}},
     * style = {b, i, u: boolean, size: "s"|"m"|"l", color, bg: "#rrggbb"}. A cell's
     * style wins over the row's. See CollabPlanColumnsService.cleanFormat.
     */
    @Column(name = "format_json", columnDefinition = "TEXT")
    private String formatJson;
}
