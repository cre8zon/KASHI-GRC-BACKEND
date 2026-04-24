package com.kashi.grc.assessment.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A reusable question in the library.
 *
 * DESIGN: This entity has NO foreign key to AssessmentSection, no weight,
 * no is_mandatory, no order_no. Those context-specific fields belong to
 * SectionQuestionMapping (join table) because they differ per section.
 *
 * One question can be mapped to many sections — zero duplication.
 *
 * tenant_id = null  → global question created by Platform Admin
 * tenant_id = X     → private question created by org X
 *
 * ── QUESTION TAG ─────────────────────────────────────────────────────────────
 * questionTag is a semantic category label — completely independent of the
 * assessment template and assessment instance. It belongs to the question
 * itself in the library, the same way a product has a category in a catalogue.
 *
 * ISOLATION CONTRACT:
 *   - questionTag lives on AssessmentQuestion (the library, the source of truth)
 *   - It is snapshotted into AssessmentQuestionInstance.questionTagSnapshot at
 *     instantiation time — so running assessments are fully isolated from
 *     subsequent tag changes on the template question
 *   - GuardRule.questionTag matches against the snapshot, never the live question
 *   - No foreign key, no join — the tag is just a string category label
 *
 * SCALABILITY CONTRACT:
 *   - One guard rule with questionTag='ENCRYPTION' covers every question in every
 *     template and every module that carries that tag — regardless of question count
 *   - Adding 1000 questions tagged 'ENCRYPTION' requires zero new guard rules
 *   - Adding a new module (Audit, Policy) with tagged questions inherits all rules
 *     automatically — no mapping table, no per-question-ID seeding
 *
 * Tag naming convention: UPPER_SNAKE_CASE, max 80 chars
 * Examples: MFA, ENCRYPTION, PEN_TEST, IRP, BCP, DRP, DATA_RETENTION,
 *           DPA, CERTIFICATION, VULN_MGMT, SEC_TRAINING, CISO,
 *           BREACH_NOTIFY, SUBPROCESSOR, INFOSEC_POLICY
 */
@Entity
@Table(name = "assessment_questions", indexes = {
        @Index(name = "idx_aq_tag", columnList = "question_tag")
})
@Getter
@Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentQuestion extends GlobalOrTenantEntity {

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "response_type", nullable = false, length = 50)
    private String responseType;  // SINGLE_CHOICE | MULTI_CHOICE | TEXT | FILE_UPLOAD

    /**
     * Semantic category tag for KashiGuard rule matching.
     *
     * Nullable — untagged questions are never evaluated by the guard system.
     * Set by Platform Admin or Org Admin in the question library.
     * Snapshotted into AssessmentQuestionInstance.questionTagSnapshot at
     * assessment instantiation time.
     */
    @Column(name = "question_tag", length = 80)
    private String questionTag;
}