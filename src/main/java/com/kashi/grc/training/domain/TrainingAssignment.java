package com.kashi.grc.training.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One thing owed by one person — a course to complete, or a policy to accept.
 *
 * ── ONE ENGINE, TWO TARGET TYPES ──────────────────────────────────────────
 * Completing a course and accepting a policy are the same act operationally:
 * give a person a thing, a deadline, and a way to prove they did it. Two
 * engines would have meant two reminder paths, two evidence tables, and two
 * different answers to "what does this person still owe us".
 *
 * ── personnel_id, NOT user_id ─────────────────────────────────────────────
 * A contractor with no login still owes the training, and the completion
 * record must outlive the account — the same reason the roster is not the user
 * table. The employee surface resolves the current login to a personnel row
 * through personnel.user_id.
 *
 * ── WHAT target_version MEANS, AND WHY IT DIFFERS BY TYPE ─────────────────
 * For POLICY it is audit_policies.version, and it is the entire point:
 * accepting v2 says nothing about v3, so publishing a new version creates a
 * new assignment rather than reopening the old one.
 *
 * For COURSE it is the recurrence cycle — 1 for the first assignment, 2 for
 * next year's. The unique key spans it, so annual retraining is a new row with
 * its own evidence rather than an overwrite of last year's, which is what an
 * auditor sampling three years of training records needs it to be.
 *
 * targetTitle is a SNAPSHOT. A course renamed two years after someone
 * completed it must not silently rewrite what their record says they did.
 */
@Entity
@Table(
        name = "training_assignments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_assignment_target",
                        columnNames = {"personnel_id", "target_type", "target_id", "target_version"})
        },
        indexes = {
                @Index(name = "idx_ta_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_ta_person",  columnList = "personnel_id,status"),
                @Index(name = "idx_ta_target",  columnList = "target_type,target_id"),
                @Index(name = "idx_ta_overdue", columnList = "tenant_id,status,due_at"),
                @Index(name = "idx_ta_status",  columnList = "status"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingAssignment extends AuditableEntity {

    @Column(name = "personnel_id", nullable = false)
    private Long personnelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private TargetType targetType;

    /** training_courses.id or audit_policies.id, per targetType. */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "target_version")
    private Integer targetVersion;

    /** Snapshot at assignment time. See class javadoc. */
    @Column(name = "target_title", length = 500)
    private String targetTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.ASSIGNED;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** Attestation and policy acceptance are the same act, differently worded. */
    @Column(name = "attested_at")
    private LocalDateTime attestedAt;

    /**
     * Captured because an attestation is a statement by a person, and "who
     * clicked it, when, from where" is the whole of its evidential weight.
     */
    @Column(name = "attestation_ip", length = 45)
    private String attestationIp;

    @Column(name = "quiz_score_percent")
    private Integer quizScorePercent;

    @Column(name = "quiz_attempts", nullable = false)
    @Builder.Default
    private Integer quizAttempts = 0;

    /** Chains an annual reassignment to the one it replaced. */
    @Column(name = "recurred_from_id")
    private Long recurredFromId;

    @Column(name = "assigned_by")
    private Long assignedBy;

    public enum TargetType { COURSE, POLICY }

    public enum Status { ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED }

    /**
     * Overdue is DERIVED, never a status. Storing it would need a sweep to keep
     * it true, and the sweep would be the bug — a record that says ASSIGNED on
     * a deadline that passed last night is simply wrong until the job runs.
     */
    @Transient
    public boolean isOverdue() {
        return dueAt != null
                && completedAt == null
                && status != Status.CANCELLED
                && dueAt.isBefore(LocalDateTime.now());
    }
}
