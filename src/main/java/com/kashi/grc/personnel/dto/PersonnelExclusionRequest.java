package com.kashi.grc.personnel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /v1/personnel/{id}/exclusions — opened by PERSON_ADD_EXCLUSION via
 * __formKey.
 *
 * reason is mandatory and deliberately so. An exclusion turns a red row green;
 * the reason is what an auditor reads when asking why.
 */
@Getter
@Setter
public class PersonnelExclusionRequest {

    @NotBlank(message = "Choose which requirement this person is excluded from")
    @Size(max = 50)
    private String requirementKey;

    @NotBlank(message = "A written reason is required to exclude someone from a requirement")
    @Size(max = 500)
    private String reason;

    /** DATE or ISO datetime. Blank means indefinite. */
    private String expiresAt;
}
