package com.kashi.grc.collab.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Change history of a plan item: who changed which field, from what to what,
 * and why. Written for dates, owner, status, title, link and deletion — so a
 * slipped date is never silent and both sides can see who moved it.
 * created_at (BaseEntity) is when.
 */
@Entity
@Table(name = "collab_plan_changes",
        indexes = {
                @Index(name = "idx_collab_pc_item", columnList = "plan_item_id"),
                @Index(name = "idx_collab_pc_ws",   columnList = "workspace_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
public class CollabPlanChange extends TenantAwareEntity {

    @Column(name = "plan_item_id", nullable = false)
    private Long planItemId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "field", nullable = false, length = 40)
    private String field;

    @Column(name = "old_value", length = 1000)
    private String oldValue;

    @Column(name = "new_value", length = 1000)
    private String newValue;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "reason", length = 1000)
    private String reason;
}
