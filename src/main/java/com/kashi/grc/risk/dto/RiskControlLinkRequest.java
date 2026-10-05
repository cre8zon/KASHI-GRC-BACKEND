package com.kashi.grc.risk.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** POST /v1/risks/{id}/controls — the RISK_LINK_CONTROL action. */
@Getter
@Setter
public class RiskControlLinkRequest {

    /** audit_controls.id — the LIBRARY control, never an instance id. */
    @NotNull(message = "controlId is required")
    private Long controlId;

    @Size(max = 500)
    private String linkNote;
}
