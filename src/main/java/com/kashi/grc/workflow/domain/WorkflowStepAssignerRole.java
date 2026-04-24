package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Assigner role for a workflow step — who DECIDES who does the work.
 *
 * Assigner roles are INDEPENDENT of the step's side. An org-side role can
 * assign a vendor-side step and vice versa. This is by design — the person
 * who coordinates an activity is often from a different part of the org than
 * the person who executes it.
 *
 * Examples:
 *   Step "VRM Acknowledges" (side=VENDOR, actorRole=VENDOR_VRM)
 *     → assignerRole=ORG_VRM_MANAGER (org side assigns to vendor)
 *
 *   Step "CISO Assigns Groups" (side=VENDOR, actorRole=VENDOR_CISO)
 *     → assignerRole=VENDOR_VRM (vendor side, previous actor assigns next)
 *     (but with resolution=PREVIOUS_ACTOR, this table is empty — not needed)
 *
 *   Step "Org Final Approval" (side=ORGANIZATION, actorRole=ORG_APPROVER)
 *     → assignerRole=ORG_HEAD (specific senior role pushes to approver)
 *
 * Only populated when assigner_resolution = PUSH_TO_ROLES.
 * For POOL / PREVIOUS_ACTOR / INITIATOR this table is empty for that step.
 */
@Entity
@Table(name = "workflow_step_assigner_roles")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class WorkflowStepAssignerRole extends BaseEntity {

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    /**
     * Role ID from the roles table.
     * Can be ANY side — not constrained to the step's actor side.
     */
    @Column(name = "role_id", nullable = false)
    private Long roleId;
}