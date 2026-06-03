package com.kashi.grc.workflow.domain;

import com.kashi.grc.common.domain.BaseEntity;
import com.kashi.grc.workflow.enums.TaskRole;
import com.kashi.grc.workflow.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * One user's assignment to act on a StepInstance.
 *
 * taskRole distinguishes WHY the task was created:
 *   ACTOR    → User does the actual work (fill, review, evaluate, approve)
 *   ASSIGNER → User drives assignment — picks who the ACTOR will be
 *
 * The same StepInstance can have both ASSIGNER and ACTOR tasks simultaneously:
 *   - ASSIGNER task: created when step starts, for the assigner role holders
 *   - ACTOR task: created when assigner delegates/assigns to a specific actor
 *
 * taskRole drives toEnrichedTaskResponse():
 *   ASSIGNER → resolvedStepAction = ASSIGN, resolvedStepSide = assigner's side
 *   ACTOR    → resolvedStepAction = step.stepAction, resolvedStepSide = step.side
 */
@Entity
@Table(name = "task_instances")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class TaskInstance extends BaseEntity {

    @Column(name = "step_instance_id", nullable = false)
    private Long stepInstanceId;

    @Column(name = "assigned_user_id", nullable = false)
    private Long assignedUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "acted_at")
    private LocalDateTime actedAt;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    /** Set on original task when DELEGATE action is performed */
    @Column(name = "delegated_to_user_id")
    private Long delegatedToUserId;

    /** Set on the NEW task created during REASSIGN or DELEGATE */
    @Column(name = "reassigned_from_user_id")
    private Long reassignedFromUserId;

    /** True when assigned via role resolution, false when explicit user assignment */
    @Column(name = "is_auto_assigned", nullable = false)
    @Builder.Default
    private boolean isAutoAssigned = false;

    /**
     * ACTOR: user does the work (stepAction drives UI).
     * ASSIGNER: user drives the assignment (always routes to ASSIGN UI).
     * Default ACTOR for backward compatibility with existing tasks.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "task_role", nullable = false, length = 20)
    @Builder.Default
    private TaskRole taskRole = TaskRole.ACTOR;

    /**
     * For ASSIGNER tasks: the side of the assigner (ORGANIZATION, VENDOR, AUDITOR...).
     * Stored at task creation time so toEnrichedTaskResponse() never needs a
     * runtime role lookup. Null for ACTOR tasks.
     */
    @Column(name = "assigner_side", length = 50)
    private String assignerSide;

    /**
     * For ACTOR tasks: the name of the role that qualified this user for this task.
     * e.g. "VENDOR_VRM", "VENDOR_CISO", "VENDOR_RESPONDER".
     * Stored at creation time so the frontend can dispatch to the correct sub-view
     * without hardcoding step numbers or role names in the UI.
     * Null for ASSIGNER tasks and direct-user tasks.
     */
    @Column(name = "actor_role_name", length = 100)
    private String actorRoleName;

    /**
     * JSON draft saved by the actor while working on a compound task.
     * Module-specific shape — the engine stores it opaquely.
     * Added by V3 migration (compound task system).
     */
    @Column(name = "draft_data", columnDefinition = "LONGTEXT")
    private String draftData;

    @Column(name = "draft_saved_at")
    private java.time.LocalDateTime draftSavedAt;
}