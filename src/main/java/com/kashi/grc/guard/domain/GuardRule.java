package com.kashi.grc.guard.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * KashiGuard — Question Trigger Rule.
 *
 * A rule says: "when any question tagged with questionTag is answered with
 * condition Y, automatically raise finding Z (from blueprint)."
 *
 * ── WHY question_id IS GONE ──────────────────────────────────────────────────
 * The old question_id column forced a one-rule-per-question mapping. With
 * thousands of questions across multiple modules (assessments, audits, policies),
 * this required thousands of rule rows and constant maintenance every time a
 * question was added or a framework was extended.
 *
 * questionTag replaces it with a one-rule-per-category approach:
 *   - One rule with questionTag='ENCRYPTION' covers ALL questions in ALL templates
 *     and ALL modules that carry that tag
 *   - Adding 500 new questions tagged 'ENCRYPTION' = zero new guard rules needed
 *   - Adding an Audit module with tagged questions = automatic rule inheritance
 *   - No mapping table, no per-question seeding, no ID maintenance ever
 *
 * ── HOW MATCHING WORKS ───────────────────────────────────────────────────────
 * At evaluation time, GuardEvaluator receives a QuestionContext that includes
 * questionTagSnapshot (copied from AssessmentQuestionInstance at instantiation).
 * GuardRuleRepository.findActiveRulesForTag() matches on that snapshot value.
 * No join to assessment_questions — complete isolation.
 *
 * ── ISOLATION CONTRACT ───────────────────────────────────────────────────────
 * GuardRule.questionTag is an independent category label — not a FK to any
 * question, template, or instance table. It is a pure semantic string.
 * The snapshot on the instance side provides the isolation guarantee:
 * changing a tag on a library question never affects rules on running instances.
 *
 * ── GLOBAL vs TENANT RULES ───────────────────────────────────────────────────
 * GlobalOrTenantEntity:
 *   tenantId = NULL → global rule (applies to all tenants)
 *   tenantId = X    → tenant-specific override (applies only to tenant X)
 * Both sets fire for the same question — global rules are the baseline,
 * tenant rules add custom findings on top.
 */
@Entity
@Table(name = "guard_rules", indexes = {
        @Index(name = "idx_gr_tag",       columnList = "question_tag"),
        @Index(name = "idx_gr_blueprint", columnList = "blueprint_code"),
        @Index(name = "idx_gr_tenant",    columnList = "tenant_id"),
})
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class GuardRule extends GlobalOrTenantEntity {

    /**
     * Semantic category tag — matches AssessmentQuestionInstance.questionTagSnapshot.
     *
     * One rule covers all questions carrying this tag, across all templates and modules.
     * Not a foreign key. Not a reference to any question ID.
     *
     * Examples: MFA, ENCRYPTION, PEN_TEST, IRP, BCP, DRP, DATA_RETENTION,
     *           DPA, CERTIFICATION, VULN_MGMT, SEC_TRAINING, CISO,
     *           BREACH_NOTIFY, SUBPROCESSOR, INFOSEC_POLICY, GDPR_CONSENT
     */
    @Column(name = "question_tag", nullable = false, length = 80)
    private String questionTag;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 30)
    private ConditionType conditionType;

    /**
     * Value to match:
     *   OPTION_SELECTED / NOT_SELECTED → option text
     *   TEXT_CONTAINS                  → keyword
     *   SCORE_BELOW / ABOVE            → numeric threshold e.g. "3.0"
     *   TEXT_EMPTY / FILE_NOT_UPLOADED / ANY_ANSWER / ANSWER_MISSING / SCORE_NOT_SET → null
     */
    @Column(name = "condition_value", length = 255)
    private String conditionValue;

    /** References action_item_blueprints.blueprint_code */
    @Column(name = "blueprint_code", nullable = false, length = 80)
    private String blueprintCode;

    /** Override which role gets the action item — null = use blueprint default */
    @Column(name = "assigned_role", length = 60)
    private String assignedRole;

    /** Override priority — null = use blueprint default */
    @Column(name = "priority_override", length = 10)
    private String priorityOverride;

    @Column(name = "rule_description", columnDefinition = "TEXT")
    private String ruleDescription;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    // ── Condition types ───────────────────────────────────────────────────────

    public enum ConditionType {
        /** Specific option was chosen */
        OPTION_SELECTED,

        /** Specific option was NOT chosen */
        OPTION_NOT_SELECTED,

        /** Free text contains keyword */
        TEXT_CONTAINS,

        /** Response text is null or blank */
        TEXT_EMPTY,

        /** No file was attached */
        FILE_NOT_UPLOADED,

        /** Numeric score < conditionValue threshold */
        SCORE_BELOW,

        /** Numeric score > conditionValue threshold */
        SCORE_ABOVE,

        /** Always fires — used for mandatory review flags */
        ANY_ANSWER,

        /**
         * Question was completely skipped — no response row at all.
         * Fires when responseText IS NULL AND fileUploaded IS FALSE AND score IS NULL.
         * Distinct from TEXT_EMPTY (which fires when a response row exists but is blank).
         */
        ANSWER_MISSING,

        /**
         * Scored question where score was not set.
         * Fires when score IS NULL regardless of responseText.
         * Used for frameworks where every question must carry a numeric score.
         */
        SCORE_NOT_SET
    }
}