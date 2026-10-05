package com.kashi.grc.asset.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PUT  /v1/assets/{id}/lifecycle — the asset_detail_tab_lifecycle form.
 * POST /v1/assets/{id}/dispose   — the ASSET_DISPOSE action reads the same two
 *                                  disposal fields, so one DTO serves both.
 */
@Getter
@Setter
public class AssetLifecycleRequest {

    private LocalDate acquiredAt;

    private LocalDate warrantyExpiry;

    /** Vendor support ends. Drives the EOL-in-production evidence. */
    private LocalDate endOfLifeDate;

    /** DATE field — "yyyy-MM-dd", or a full ISO datetime. Parsed leniently. */
    private String lastReviewedAt;

    /** Option set asset_disposal_options. Required to dispose. */
    @Size(max = 50)
    private String disposalMethod;

    /** Certificate number, ticket ref or document id. Required to dispose. */
    @Size(max = 300)
    private String disposalEvidenceRef;

    /** Free text from actions carrying requires_remarks (ASSET_RETIRE, ASSET_REINSTATE). */
    private String remarks;
}
