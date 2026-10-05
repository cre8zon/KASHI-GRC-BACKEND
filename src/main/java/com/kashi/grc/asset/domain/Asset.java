package com.kashi.grc.asset.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An asset in the inventory — ISO 27001 A.5.9, UCF domain AST.
 *
 * ── WHY AuditableEntity AND NOT GlobalOrTenantEntity ──────────────────────
 * Risk extends GlobalOrTenantEntity because a platform library scenario has
 * tenant_id NULL. Assets have no library: nobody else's server is a useful
 * starting point for yours. tenant_id is NOT NULL, so the standard
 * tenant-scoped base class applies and brings created_by / updated_by /
 * is_deleted with it.
 *
 * ── VALUATION ─────────────────────────────────────────────────────────────
 * ISO 27005 values an asset in three dimensions. A single "criticality" field
 * collapses three different questions — what if it leaks, what if it is
 * altered, what if it stops — into one answer and loses the reason behind it.
 * So C, I and A are stored at 1-5 and criticality is DERIVED from the highest
 * of the three by AssetService, the same way inherentScore is derived on Risk.
 * Never trust a criticality supplied by the client.
 *
 * ── COMPOSITION ───────────────────────────────────────────────────────────
 * parentAssetId drives EntityTreeView through blueprint supports_tree = 1.
 * The list screen IS the hierarchy; there is no components tab and no
 * frontend code. The API must return `parentId` for that to work — see
 * AssetController.list, which maps parentAssetId to that name deliberately.
 */
@Entity
@Table(
        name = "assets",
        indexes = {
                @Index(name = "idx_asset_tenant", columnList = "tenant_id"),
                @Index(name = "idx_asset_status", columnList = "status"),
                @Index(name = "idx_asset_type",   columnList = "asset_type"),
                @Index(name = "idx_asset_owner",  columnList = "owner_id"),
                @Index(name = "idx_asset_parent", columnList = "parent_asset_id"),
                @Index(name = "idx_asset_vendor", columnList = "vendor_id"),
                @Index(name = "idx_asset_crit",   columnList = "criticality"),
                @Index(name = "idx_asset_eol",    columnList = "end_of_life_date"),
                @Index(name = "idx_asset_review", columnList = "next_review_date"),
                @Index(name = "idx_asset_pii",    columnList = "contains_personal_data"),
                @Index(name = "idx_asset_tags",   columnList = "control_tags"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Asset extends AuditableEntity {

    // ── Identity ──────────────────────────────────────────────────────────────

    @Column(name = "asset_ref", length = 30)
    private String assetRef;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Option set asset_type_options — SERVER, SAAS, DATABASE, … */
    @Column(name = "asset_type", length = 50)
    private String assetType;

    /** Option set asset_class_options — the five ISO 27005 classes. */
    @Column(name = "asset_class", length = 30)
    private String assetClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private Status status = Status.ACTIVE;

    // ── Valuation ─────────────────────────────────────────────────────────────

    @Column(name = "confidentiality_rating")
    private Integer confidentialityRating;

    @Column(name = "integrity_rating")
    private Integer integrityRating;

    @Column(name = "availability_rating")
    private Integer availabilityRating;

    /** Derived from the three ratings. Written by the service, never the client. */
    @Column(name = "criticality", length = 20)
    private String criticality;

    @Column(name = "classification", length = 30)
    private String classification;

    // ── Ownership ─────────────────────────────────────────────────────────────

    /** Accountable for the asset and for accepting risk on it. */
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "owner_team", length = 200)
    private String ownerTeam;

    /** Operates it day to day. A different role from owner, and both are audited. */
    @Column(name = "custodian_id")
    private Long custodianId;

    // ── Placement ─────────────────────────────────────────────────────────────

    @Column(name = "environment", length = 30)
    private String environment;

    @Column(name = "hosting_model", length = 30)
    private String hostingModel;

    @Column(name = "location", length = 300)
    private String location;

    /** Where the data physically sits. DPDPA and RBI localisation both ask. */
    @Column(name = "data_residency", length = 100)
    private String dataResidency;

    /** Hostname, URL or instance id — for reconciling against a discovery tool. */
    @Column(name = "asset_identifier", length = 200)
    private String assetIdentifier;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    // ── Cross-links (zero-FK, like the rest of the schema) ────────────────────

    /** vendors.id — the supplier or host behind this asset, if any. */
    @Column(name = "vendor_id")
    private Long vendorId;

    /** assets.id — an app on a server, a server in a rack. Drives the tree. */
    @Column(name = "parent_asset_id")
    private Long parentAssetId;

    // ── Privacy ───────────────────────────────────────────────────────────────

    @Column(name = "contains_personal_data", nullable = false)
    @Builder.Default
    private boolean containsPersonalData = false;

    /** Comma-separated, from asset_pii_category_options. */
    @Column(name = "personal_data_categories", length = 500)
    private String personalDataCategories;

    // ── UCF anchor ────────────────────────────────────────────────────────────

    /** Comma-separated common_controls codes, same vocabulary as risks.control_tags. */
    @Column(name = "control_tags", length = 500)
    private String controlTags;

    @Column(name = "framework_refs", length = 500)
    private String frameworkRefs;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Column(name = "acquired_at")
    private LocalDate acquiredAt;

    @Column(name = "warranty_expiry")
    private LocalDate warrantyExpiry;

    /**
     * Vendor support ends. This is the column that makes "End-of-life software
     * remains in production" (RSK-L-098 / VUL-02.1) a query rather than an
     * opinion, which is most of the reason the module is worth having.
     */
    @Column(name = "end_of_life_date")
    private LocalDate endOfLifeDate;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    @Column(name = "disposed_at")
    private LocalDateTime disposedAt;

    /** Option set asset_disposal_options. Required to dispose — evidences AST-03.3. */
    @Column(name = "disposal_method", length = 50)
    private String disposalMethod;

    /** Certificate number, ticket ref or document id. Also required to dispose. */
    @Column(name = "disposal_evidence_ref", length = 300)
    private String disposalEvidenceRef;

    // ── Review cycle ──────────────────────────────────────────────────────────

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "next_review_date")
    private LocalDate nextReviewDate;

    @Column(name = "review_frequency_months")
    @Builder.Default
    private Integer reviewFrequencyMonths = 12;

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Must stay identical to module_blueprints.status_flow_json for ASSET.
     * A status here that is absent there renders no transition buttons at all.
     *
     * DISPOSED is terminal on purpose: an asset whose disposal has been
     * evidenced must not quietly come back into service, because the disposal
     * certificate says it was destroyed.
     */
    public enum Status {
        ACTIVE,
        IN_REPAIR,
        IN_STORAGE,
        RETIRED,
        DISPOSED
    }
}
