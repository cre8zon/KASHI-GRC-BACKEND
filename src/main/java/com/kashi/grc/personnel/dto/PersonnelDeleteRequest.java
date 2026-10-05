package com.kashi.grc.personnel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * DELETE /v1/personnel/{id} — soft delete, reason required.
 *
 * Deletion is for records created in error: a duplicate, a typo, someone
 * entered against the wrong tenant. PersonnelService refuses to delete anyone
 * who has ever been ACTIVE, because their offboarding evidence is the artifact
 * an auditor tests and removing it defeats the module.
 */
@Getter
@Setter
public class PersonnelDeleteRequest {

    @NotBlank(message = "A reason is required to remove a roster record")
    @Size(max = 500)
    private String reason;
}
