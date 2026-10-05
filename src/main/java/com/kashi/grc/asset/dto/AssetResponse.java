package com.kashi.grc.asset.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detail payload for GET /v1/assets/{id}.
 *
 * Property names are the field_key values from asset_detail_header,
 * _tab_overview, _tab_valuation and _tab_lifecycle. UniversalModulePage reads
 * entity[field.fieldKey] directly, so a name that does not match renders blank
 * with no error anywhere.
 *
 * ── WHY THE CIA RATINGS GO OUT AS STRINGS ─────────────────────────────────
 * The valuation form renders them as SELECTs over asset_cia_options, whose
 * option values are the strings "1".."5". A select compares its options to the
 * bound value with ===, so an Integer 3 matches no option and the field
 * renders empty even though a value is stored. They go out as text for the
 * same reason they come in as text.
 */
@Getter
@Builder
public class AssetResponse {

    private Long    id;
    private String  assetRef;
    private String  name;
    private String  description;
    private String  assetType;
    private String  assetClass;
    private String  status;

    // ── Valuation ─────────────────────────────────────────────────────────────
    private String  confidentialityRating;
    private String  integrityRating;
    private String  availabilityRating;
    private String  criticality;
    private String  classification;

    // ── Ownership ─────────────────────────────────────────────────────────────
    private Long    ownerId;
    private String  ownerName;
    private String  ownerTeam;
    private Long    custodianId;
    private String  custodianName;

    // ── Placement ─────────────────────────────────────────────────────────────
    private String  environment;
    private String  hostingModel;
    private String  location;
    private String  dataResidency;
    private String  assetIdentifier;
    private String  serialNumber;

    // ── Cross-links ───────────────────────────────────────────────────────────
    private Long    vendorId;
    private String  vendorName;
    private Long    parentAssetId;
    private String  parentAssetName;

    /**
     * EntityTreeView builds the hierarchy from `parentId`, not parentAssetId.
     * Both are emitted: parentId for the tree, parentAssetId for the overview
     * form field of the same name. One name would break one of the two.
     */
    private Long    parentId;
    private Integer childCount;

    // ── Privacy ───────────────────────────────────────────────────────────────
    private Boolean containsPersonalData;
    private String  personalDataCategories;

    // ── UCF anchor ────────────────────────────────────────────────────────────
    private String  controlTags;
    private String  frameworkRefs;

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    private LocalDate     acquiredAt;
    private LocalDate     warrantyExpiry;
    private LocalDate     endOfLifeDate;
    private LocalDateTime retiredAt;
    private LocalDateTime disposedAt;
    private String        disposalMethod;
    private String        disposalEvidenceRef;

    /** True when end_of_life_date has passed and the asset is still in service. */
    private Boolean pastEndOfLife;

    // ── Review cycle ──────────────────────────────────────────────────────────
    private LocalDateTime lastReviewedAt;
    private LocalDate     nextReviewDate;
    private Integer       reviewFrequencyMonths;
    private Boolean       reviewOverdue;

    // ── Audit ─────────────────────────────────────────────────────────────────
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long          createdBy;

    /**
     * False once the asset is DISPOSED. UniversalModulePage drops the
     * collaboration surface when this is false, which is right: a disposal
     * certificate says the thing was destroyed, and a record that keeps
     * changing afterwards is not evidence of anything.
     */
    private Boolean editable;

    // ── Linked risks ──────────────────────────────────────────────────────────
    private List<LinkedRisk> linkedRisks;
    private Integer          linkedRiskCount;

    /**
     * One risk this asset is exposed to. Shaped for the generic
     * LinkedEntitiesTab, which reads id / ref / title / status / badge and
     * navigates to navEntityType.
     */
    @Getter
    @Builder
    public static class LinkedRisk {
        private Long   linkId;
        private Long   id;
        private String ref;
        private String title;
        private String status;
        private String badge;
        private String linkNote;
        private String navEntityType;
    }
}
