package com.kashi.grc.training.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * POST /v1/training/assignments/policy — assign an approved policy for reading
 * and acceptance.
 *
 * Nothing is written to audit_policies. The service reads the policy, refuses
 * anything not APPROVED, and pins the version onto the assignment — so
 * publishing v3 later creates fresh assignments rather than reopening the v2
 * acceptances, which would be a lie about what people agreed to.
 */
@Getter @Setter
public class TrainingPolicyAssignRequest {

    @NotNull(message = "policyId is required")
    private Long policyId;

    private List<Long> personnelIds;

    private String department;

    private Integer dueInDays;
}
