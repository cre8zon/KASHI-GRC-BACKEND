package com.kashi.grc.asset.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** POST /v1/assets/{id}/risks — the ASSET_LINK_RISK action. */
@Getter
@Setter
public class AssetRiskLinkRequest {

    /** risks.id — must be one of this tenant's own risks, never a library row. */
    @NotNull(message = "riskId is required")
    private Long riskId;

    @Size(max = 500)
    private String linkNote;
}
