package com.kashi.grc.risk.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * POST /v1/risks/library/adopt-all — the risk_adopt_all_form.
 *
 * Both fields are optional. ownerId defaults to the caller, matching what the
 * form's helper text promises.
 */
@Getter
@Setter
public class RiskAdoptRequest {

    private Long ownerId;

    private String ownerTeam;
}
