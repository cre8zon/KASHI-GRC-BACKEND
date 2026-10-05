package com.kashi.grc.incident.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /v1/incidents/{id}/assets and /risks.
 *
 * One DTO for both because the shape is identical apart from which id is set;
 * the service validates that exactly one is present. Two near-identical
 * classes would drift.
 */
@Getter
@Setter
public class IncidentLinkRequest {

    /** assets.id — for POST /assets. */
    private Long assetId;

    /** risks.id — for POST /risks. Must be the tenant's own, never a library row. */
    private Long riskId;

    @Size(max = 500)
    private String note;
}
