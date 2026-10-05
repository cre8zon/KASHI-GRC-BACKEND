package com.kashi.grc.asset.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * PUT /v1/assets/{id}/valuation — the asset_detail_tab_valuation form.
 *
 * criticality is accepted and DISCARDED: AssetService recomputes it from the
 * highest of the three CIA ratings. A stored criticality that disagrees with
 * its own factors is the defect nobody notices until an auditor asks how a
 * 5/5/5 asset came out as Medium.
 *
 * The CIA fields arrive as Strings because the form renders them as SELECTs
 * over asset_cia_options ("1".."5"), and a select posts its option value as
 * text. Parsed and range-checked in the service rather than relying on
 * Jackson to coerce, so a bad value gives a readable message.
 */
@Getter
@Setter
public class AssetValuationRequest {

    private String confidentialityRating;
    private String integrityRating;
    private String availabilityRating;

    /** Accepted and ignored — recomputed. Present so the form POST does not 400. */
    private String criticality;

    @Size(max = 30)
    private String classification;

    private Boolean containsPersonalData;

    @Size(max = 500)
    private String personalDataCategories;

    @Min(1) @Max(120)
    private Integer reviewFrequencyMonths;

    private LocalDate nextReviewDate;
}
