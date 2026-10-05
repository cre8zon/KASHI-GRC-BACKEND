package com.kashi.grc.training.dto;

import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /v1/training/assignments/{id}/attest — the final act, for a course
 * attestation or a policy acceptance alike.
 *
 * confirmed must be true. A request that does not carry an explicit
 * affirmation is not an attestation, and defaulting it would make the record
 * worthless.
 */
@Getter @Setter
public class TrainingAttestRequest {

    @AssertTrue(message = "Confirm that you have read and understood the material")
    private Boolean confirmed;
}
