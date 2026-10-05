package com.kashi.grc.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Create (POST /v1/assets) and generic update (PUT /v1/assets/{id}).
 *
 * Property names match the field_key values on asset_create_form,
 * asset_detail_header and asset_detail_tab_overview exactly. A mismatch
 * renders blank and saves nothing, with no error on either side.
 *
 * STATUS AND CRITICALITY ARE NOT WRITABLE HERE.
 * The header form shows both for display. Submitting the value already on
 * screen is a no-op; submitting a different one is refused. Status has
 * dedicated lifecycle endpoints that record timestamps and enforce legality;
 * criticality is derived from the CIA ratings on the valuation tab.
 */
@Getter
@Setter
public class AssetRequest {

    @NotBlank(message = "Asset name is required")
    @Size(max = 500)
    private String name;

    private String description;

    @Size(max = 30)
    private String assetRef;

    @Size(max = 50)
    private String assetType;

    @Size(max = 30)
    private String assetClass;

    /** Accepted only when equal to the current status. See class javadoc. */
    private String status;

    /** Accepted only when equal to the current value — it is derived. */
    private String criticality;

    @Size(max = 30)
    private String classification;

    private Long ownerId;

    @Size(max = 200)
    private String ownerTeam;

    private Long custodianId;

    @Size(max = 30)
    private String environment;

    @Size(max = 30)
    private String hostingModel;

    @Size(max = 300)
    private String location;

    @Size(max = 100)
    private String dataResidency;

    @Size(max = 200)
    private String assetIdentifier;

    @Size(max = 120)
    private String serialNumber;

    /** vendors.id */
    private Long vendorId;

    /** assets.id — validated against cycles by AssetService. */
    private Long parentAssetId;

    private Boolean containsPersonalData;

    /** TAG field — DynamicForm joins the array to a comma-separated string. */
    @Size(max = 500)
    private String personalDataCategories;

    @Size(max = 500)
    private String controlTags;

    @Size(max = 500)
    private String frameworkRefs;

    private LocalDate nextReviewDate;

    private Integer reviewFrequencyMonths;
}
