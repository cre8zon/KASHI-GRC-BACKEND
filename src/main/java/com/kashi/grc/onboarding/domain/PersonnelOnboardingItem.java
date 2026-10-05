package com.kashi.grc.onboarding.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One checklist line, for one person.
 *
 * ── THE TITLE IS A SNAPSHOT, NOT A JOIN ───────────────────────────────────
 * Same reason the training module pins a policy version: editing the template
 * must not rewrite what somebody already completed. "Signed the 2025 NDA" has
 * to keep saying that after the template is updated to the 2026 one, or the
 * completion record quietly becomes a claim about a document nobody signed.
 *
 * ── AN INCOMPLETE CHECKLIST DOES NOT BLOCK ANYTHING ───────────────────────
 * Items are created at PENDING_START and go overdue on their own clock. They
 * feed the compliance rollup; they do not gate ACTIVE.
 *
 * People start work before the paperwork finishes — that is simply true, and a
 * system that pretends otherwise gets worked around within a week: somebody
 * ticks every box on day one to unblock the hire, and the checklist becomes a
 * formality recording nothing. Offboarding is rightly the opposite, because
 * there the person is leaving and there is no next chance to collect evidence.
 */
@Entity
@Table(
        name = "personnel_onboarding_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_person_template_item", columnNames = {"personnel_id", "template_item_id"}),
        indexes = {
                @Index(name = "idx_onb_person",  columnList = "personnel_id,status"),
                @Index(name = "idx_onb_overdue", columnList = "tenant_id,status,due_at"),
        })
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class PersonnelOnboardingItem extends TenantAwareEntity {

    @Column(name = "personnel_id", nullable = false)
    private Long personnelId;

    /** Null for an item added by hand for one person, which is legitimate. */
    @Column(name = "template_item_id")
    private Long templateItemId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String category = "ADMIN";

    @Column(name = "owner_role", length = 100)
    private String ownerRole;

    @Column(name = "is_required", nullable = false)
    @Builder.Default
    private boolean isRequired = true;

    @Column(name = "requires_evidence", nullable = false)
    @Builder.Default
    private boolean requiresEvidence = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "evidence_document_id")
    private Long evidenceDocumentId;

    /**
     * Required to WAIVE.
     *
     * A waiver with no reason is indistinguishable from somebody clearing their
     * queue, and it is exactly the row an auditor samples.
     */
    @Column(name = "waiver_reason", length = 500)
    private String waiverReason;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    public enum Status { PENDING, DONE, WAIVED, NOT_APPLICABLE }

    /** Outstanding AND past its date. Not-applicable and waived are settled. */
    @Transient
    public boolean isOverdue() {
        return status == Status.PENDING
                && dueAt != null
                && dueAt.isBefore(LocalDateTime.now());
    }

    /** Counts towards "is this person onboarded". Waived counts as settled. */
    @Transient
    public boolean isSettled() {
        return status == Status.DONE || status == Status.WAIVED
                || status == Status.NOT_APPLICABLE;
    }
}