package com.kashi.grc.guard.dto;

import com.kashi.grc.guard.domain.GuardRule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request DTO for creating or updating a guard rule.
 *
 * questionTag replaces the old questionId field.
 * Set it to the semantic category of the questions this rule should cover.
 * Examples: MFA, ENCRYPTION, PEN_TEST, IRP, BCP, DATA_RETENTION
 *
 * One rule with a given questionTag fires for ALL question instances across ALL
 * templates and modules that carry that tag in their questionTagSnapshot.
 */
@Data
public class GuardRuleRequest {

    /**
     * The question category this rule targets.
     * Must match exactly (case-sensitive) the questionTag values set on AssessmentQuestion
     * and snapshotted into AssessmentQuestionInstance.questionTagSnapshot.
     */
    @NotBlank
    private String questionTag;

    @NotNull
    private GuardRule.ConditionType conditionType;

    /** Null for TEXT_EMPTY, FILE_NOT_UPLOADED, ANY_ANSWER, ANSWER_MISSING, SCORE_NOT_SET */
    private String conditionValue;

    @NotBlank
    private String blueprintCode;

    private String  assignedRole;
    private String  priorityOverride;
    private String  ruleDescription;
    private Boolean isActive = true;
}