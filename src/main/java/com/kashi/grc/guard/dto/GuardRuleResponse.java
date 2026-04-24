package com.kashi.grc.guard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kashi.grc.guard.domain.GuardRule;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GuardRuleResponse {
    private Long                    id;
    private Long                    tenantId;        // null = global rule
    private Boolean                 isGlobal;        // convenience: tenantId == null
    private String                  questionTag;     // the category tag this rule covers

    /**
     * How many library questions currently carry this tag.
     * Tells the admin the rule's reach — e.g. "Covers 12 questions".
     * Replaces the old single questionText field: a tag covers many questions,
     * so showing one question's text would be misleading.
     * Count is live — changes when questions are tagged/untagged in the library.
     */
    private Integer                 questionCount;

    private GuardRule.ConditionType conditionType;
    private String                  conditionValue;
    private String                  blueprintCode;
    private String                  blueprintTitle;  // enriched from blueprint table
    private String                  assignedRole;
    private String                  priorityOverride;
    private String                  ruleDescription;
    private Boolean                 isActive;
    private LocalDateTime           createdAt;
    private LocalDateTime           updatedAt;
}