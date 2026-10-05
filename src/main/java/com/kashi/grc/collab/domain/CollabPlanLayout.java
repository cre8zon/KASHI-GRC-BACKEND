package com.kashi.grc.collab.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The plan sheet's columns for one workspace: order, labels, widths, which
 * built-in columns are hidden, and the workspace's own columns (text, number,
 * date, dropdown, person, checkbox). One row per workspace; no row means the
 * default layout. Values of custom columns live on each item
 * (CollabPlanItem.customJson). See CollabPlanColumnsService.
 */
@Entity
@Table(name = "collab_plan_layouts",
        uniqueConstraints = @UniqueConstraint(name = "uk_collab_pl_ws", columnNames = "workspace_id"))
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabPlanLayout extends TenantAwareEntity {

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** JSON array of column definitions — see CollabPlanColumnsService. */
    @Column(name = "columns_json", columnDefinition = "TEXT")
    private String columnsJson;

    @Column(name = "updated_by")
    private Long updatedBy;
}