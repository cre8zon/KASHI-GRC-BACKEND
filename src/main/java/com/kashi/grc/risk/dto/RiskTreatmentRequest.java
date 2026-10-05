package com.kashi.grc.risk.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * PUT  /v1/risks/{id}/treatment      — the risk_detail_tab_treatment form.
 * POST /v1/risks/{id}/plan-treatment — the RISK_PLAN_TREATMENT action.
 * POST /v1/risks/{id}/accept         — the RISK_ACCEPT action.
 *
 * The acceptance fields carry depends_on_json on the form so they only appear
 * when treatmentOption is RETAIN. That is a display rule, not a security one —
 * the service re-checks it, because a hidden field is still a submittable one.
 */
@Getter
@Setter
public class RiskTreatmentRequest {

    /** MODIFY / RETAIN / AVOID / SHARE — risk_treatment_options. */
    @Size(max = 30)
    private String treatmentOption;

    private String treatmentPlan;

    /** DATE field — "yyyy-MM-dd", or a full ISO datetime. */
    private String treatedAt;

    private String acceptanceJustification;

    private Long acceptedById;

    /** DATE field — "yyyy-MM-dd", or a full ISO datetime. */
    private String acceptedAt;

    /**
     * Free-text reason carried by actions with requires_remarks = 1
     * (RISK_ACCEPT, RISK_REOPEN). UniversalModulePage posts it as "remarks".
     */
    private String remarks;
}
