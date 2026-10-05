package com.kashi.grc.personnel.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PUT /v1/personnel/{id}/employment — the employment tab.
 *
 * Carries the offboarding evidence as well as the dates, because they are
 * reconstructed together: nobody fills in "access revoked at" while revoking
 * access, they fill it in afterwards from the ticket.
 */
@Getter
@Setter
public class PersonnelEmploymentRequest {

    private LocalDate startDate;
    private LocalDate lastWorkingDay;
    private LocalDate endDate;

    /** DATE or ISO datetime. */
    private String noticeGivenAt;

    @Size(max = 30)
    private String separationReason;

    // ── Offboarding evidence ──────────────────────────────────────────────────
    private String accessRevokedAt;
    private String credentialsRotatedAt;
    private String exitInterviewAt;

    @Size(max = 300)
    private String offboardingEvidenceRef;
}
