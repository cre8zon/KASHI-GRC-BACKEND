package com.kashi.grc.risk.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * PUT /v1/risks/{id}/assessment  — the risk_detail_tab_assessment form.
 * POST /v1/risks/{id}/assess     — the RISK_ASSESS action (same body, optional).
 *
 * inherentScore and residualScore appear on the form as NUMBER fields with
 * helper text saying they are calculated on save, and the form will POST
 * whatever is in them. They are accepted into this DTO and then DISCARDED:
 * the service recomputes both from likelihood x impact. A stored score that
 * disagrees with its own factors is the kind of defect nobody notices until an
 * auditor asks how a 4x4 risk scored 9.
 */
@Getter
@Setter
public class RiskAssessmentRequest {

    @Min(1) @Max(5)
    private Integer inherentLikelihood;

    @Min(1) @Max(5)
    private Integer inherentImpact;

    @Min(1) @Max(5)
    private Integer residualLikelihood;

    @Min(1) @Max(5)
    private Integer residualImpact;

    private String assessmentNotes;

    /** DATE field — "yyyy-MM-dd", or a full ISO datetime. Parsed leniently. */
    private String assessedAt;

    /** Accepted and ignored — recomputed. Present so the form POST does not 400. */
    private Integer inherentScore;

    /** Accepted and ignored — recomputed. */
    private Integer residualScore;
}
