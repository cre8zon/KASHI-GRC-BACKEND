package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Snapshot of an AssessmentQuestion at the moment a VendorAssessment is triggered.
 * Includes the context-specific weight and mandatory flag from SectionQuestionMapping.
 * Once created, NEVER updated (except for the two assignment columns below).
 *
 * ── ASSIGNMENT COLUMNS ─────────────────────────────────────────────────────────
 *
 * assignedUserId         — VENDOR CONTRIBUTOR (step 4)
 *   Set by the Responder when distributing questions to Contributors for answering.
 *   Drives: GET /my-questions (contributor inbox), sub-task creation, section-lock bypass.
 *   Never touched after vendor fill completes.
 *
 * reviewerAssignedUserId — REVIEW ASSISTANT (step 9)
 *   Set by the Reviewer when delegating a specific question to a review assistant.
 *   Entirely separate from the vendor-side assignment — never overwrites assignedUserId.
 *   Drives: AssignToAssistantInline display on org review page.
 *
 * Keeping these separate means:
 *   - Audit trail of who answered what is preserved across send-back cycles.
 *   - The re-answer flow (contributor gets a revision task) always finds the right person.
 *   - Future reporting ("who contributed to section X?") works correctly.
 *   - New assessment cycles start with both fields null — no carry-over confusion.
 */
@Entity
@Table(name = "assessment_question_instances")
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentQuestionInstance extends BaseEntity {

    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;

    /** Groups this question under its section snapshot */
    @Column(name = "section_instance_id", nullable = false)
    private Long sectionInstanceId;

    /** Audit reference — never used for display */
    @Column(name = "original_question_id")
    private Long originalQuestionId;

    @Column(name = "question_text_snapshot", nullable = false, columnDefinition = "TEXT")
    private String questionTextSnapshot;

    @Column(name = "response_type", nullable = false, length = 50)
    private String responseType;

    /** Snapshotted from SectionQuestionMapping.weight at trigger time */
    @Column(name = "weight")
    private Double weight;

    /** Snapshotted from SectionQuestionMapping.isMandatory at trigger time */
    @Column(name = "is_mandatory", nullable = false)
    @Builder.Default
    private boolean isMandatory = false;

    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;

    /**
     * VENDOR CONTRIBUTOR assignment — Step 4.
     * Set by the Responder when assigning a question to a Contributor for answering.
     * Null until the Responder makes an assignment.
     * Used by GET /v1/assessments/{id}/my-questions to filter for the contributor's inbox.
     * NEVER overwritten by org-side review actions.
     */
    @Column(name = "assigned_user_id")
    private Long assignedUserId;

    /**
     * REVIEW ASSISTANT assignment — Step 9.
     * Set by the Reviewer when delegating a specific question to a review assistant.
     * Completely independent from assignedUserId — different phase, different actor.
     * Null until the Reviewer makes an org-side assignment.
     * Displayed in AssignToAssistantInline on the org review page.
     */
    @Column(name = "reviewer_assigned_user_id")
    private Long reviewerAssignedUserId;

    /**
     * Semantic category tag — snapshotted from AssessmentQuestion.questionTag at
     * assessment instantiation time. KashiGuard reads this snapshot, never the
     * library question directly — full isolation from future tag changes.
     * Null for questions that were not tagged when the assessment was triggered.
     */
    @Column(name = "question_tag_snapshot", length = 80)
    private String questionTagSnapshot;
}