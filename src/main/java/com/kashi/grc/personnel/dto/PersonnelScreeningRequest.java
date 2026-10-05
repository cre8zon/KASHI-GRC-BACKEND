package com.kashi.grc.personnel.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * PUT /v1/personnel/{id}/screening — the screening tab, behind personnel:screen.
 *
 * Separated from the general update DTO on purpose. Background check outcomes
 * are the most sensitive field on the record; if they were writable through
 * PersonnelRequest, the separate permission would be decorative.
 */
@Getter
@Setter
public class PersonnelScreeningRequest {

    /** NOT_REQUIRED / NOT_STARTED / IN_PROGRESS / CLEARED / ADVERSE_FINDING / WAIVED. */
    @Size(max = 30)
    private String backgroundCheckStatus;

    private String backgroundCheckCompletedAt;

    /** Vendor case number. Never the report. */
    @Size(max = 200)
    private String backgroundCheckReference;

    private String ndaSignedAt;
    private String acceptableUseAcceptedAt;
    private String codeOfConductAcceptedAt;
}
