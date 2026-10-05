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

    /**
     * Does answering this question require a document to be attached?
     *
     * ── WHY THIS IS NOT is_mandatory ─────────────────────────────────────
     * is_mandatory already exists, on section_question_mappings, and it means
     * the ANSWER is required. That is a different requirement and the two are
     * routinely independent: "Describe your incident response process" can be
     * mandatory with no attachment, and "Attach your latest penetration test
     * report, if one exists" can be optional but must come with the file when
     * it is answered at all.
     *
     * Before this column the only signal was response_type = 'FILE_UPLOAD',
     * which means something stronger — the file IS the answer and there is no
     * text field at all. There was no way to say "answer this AND attach
     * something", which is most of a real security questionnaire.
     *
     * ── WHY HERE AND NOT ON THE MAPPING ──────────────────────────────────
     * weight and is_mandatory live on section_question_mappings because they
     * are properties of using a question in a particular template. Whether a
     * question needs evidence is a property of the QUESTION — "attach your ISO
     * certificate" needs the certificate in every template it appears in — so
     * it belongs beside question_tag, authored once in the library.
     *
     * The cost of that choice, stated rather than discovered later: there is no
     * per-template override. If one template ever needs the same question
     * without its evidence requirement, this has to move to the mapping and be
     * snapshotted from there instead.
     *
     * Snapshotted into AssessmentQuestionInstance.requiresEvidence at
     * instantiation, like questionTag, so changing it later does not rewrite
     * the rules under a running assessment.
     */
    @Column(name = "requires_evidence", nullable = false)
    @lombok.Builder.Default
    private boolean requiresEvidence = false;
}