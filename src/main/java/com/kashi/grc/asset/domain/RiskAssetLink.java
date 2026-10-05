package com.kashi.grc.asset.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Risk <-> Asset. ISO 27005 asset-based risk identification.
 *
 * ── WHY A TABLE AND NOT A COLUMN ON EITHER SIDE ───────────────────────────
 * The relationship is many-to-many in both directions: one database carries
 * several risks, and "unpatched critical vulnerability" applies to many
 * servers. A column on either entity would force one of those to be a lie.
 *
 * ── WHY IT LIVES IN THE ASSET PACKAGE ─────────────────────────────────────
 * Asset is the newer module and owns the linking behaviour; Risk only reads
 * through it. Putting it in risk/ would mean the older module grew a
 * dependency on the newer one, which is the wrong direction for a module that
 * shipped first and works without assets.
 *
 * Zero-FK, consistent with the rest of the schema. tenant_id is NOT NULL on
 * both sides of the link, because neither a risk copy nor an asset is ever
 * global — only platform LIBRARY risks are, and those cannot be linked.
 */
@Entity
@Table(
        name = "risk_asset_links",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_risk_asset", columnNames = {"risk_id", "asset_id"})
        },
        indexes = {
                @Index(name = "idx_ral_risk",   columnList = "risk_id"),
                @Index(name = "idx_ral_asset",  columnList = "asset_id"),
                @Index(name = "idx_ral_tenant", columnList = "tenant_id"),
        }
)
@Getter @Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssetLink extends TenantAwareEntity {

    @Column(name = "risk_id", nullable = false)
    private Long riskId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "link_note", length = 500)
    private String linkNote;

    @Column(name = "created_by")
    private Long createdBy;
}
