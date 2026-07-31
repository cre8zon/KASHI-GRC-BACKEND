package com.kashi.grc.ucf.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A common control — one entry in the framework-agnostic catalogue.
 *
 * ── WHAT THIS IS NOT ────────────────────────────────────────────────────────
 * This is NOT an audit control. It is never mapped to a section, never pulled
 * into a template, never instantiated into an engagement, never tested. It has
 * no framework and no evidence of its own.
 *
 * audit_controls  = a framework's actual requirement ("SOC 2 CC6.1"). Tested.
 * common_controls = the semantic idea behind it ("MFA on privileged accounts").
 *
 * Many framework controls across many frameworks point at one common control
 * via audit_controls.common_control_code. That pointer is what lets one piece
 * of evidence satisfy SOC 2, ISO 27001 and RBI ITGRC at once.
 *
 * ── HIERARCHY ───────────────────────────────────────────────────────────────
 * Three levels, parent_code self-referencing:
 *
 *   DOMAIN   IAM        Identity & Access Management
 *   FAMILY   IAM-02     Authentication
 *   CONTROL  IAM-02.3   MFA — privileged / administrative
 *
 * Library controls should point at CONTROL-level leaves. Pointing at a FAMILY
 * or DOMAIN works but under-performs: matched_tags_snapshot expands to a node's
 * ANCESTORS, not its descendants, so a control sitting at IAM-02 is reachable
 * by evidence tagged IAM-02 or IAM but not by evidence tagged IAM-02.3.
 *
 * ── SCOPE ───────────────────────────────────────────────────────────────────
 * tenant_id NULL = global platform catalogue (the normal case).
 * tenant_id set  = private entry authored by one organisation.
 * Deliberately not a TenantAwareEntity: the global rows must be readable by
 * every tenant, which TenantIsolationAspect would otherwise prevent.
 */
@Entity
@Table(name = "common_controls", indexes = {
        @Index(name = "idx_cc_parent", columnList = "parent_code"),
        @Index(name = "idx_cc_domain", columnList = "domain_code"),
        @Index(name = "idx_cc_legacy", columnList = "legacy_tag"),
        @Index(name = "idx_cc_level",  columnList = "node_level")
})
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class CommonControl extends BaseEntity {

    /** Stable business key — 'IAM-02.3'. This is what audit_controls stores. */
    @Column(name = "code", nullable = false, length = 40, unique = true)
    private String code;

    /** Self-referencing parent. NULL for DOMAIN nodes. */
    @Column(name = "parent_code", length = 40)
    private String parentCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_level", nullable = false, length = 10)
    private NodeLevel nodeLevel;

    /** Denormalised root domain, so filtering by domain needs no recursion. */
    @Column(name = "domain_code", nullable = false, length = 10)
    private String domainCode;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * The free-text control_tag this entry replaces, e.g. 'MFA_ADMIN'.
     * Kept for display and for reconciling anything still carrying old tags.
     * One value only — where several legacy tags mean the same thing, the
     * others live in common_control_aliases.
     */
    @Column(name = "legacy_tag", length = 80)
    private String legacyTag;

    @Column(name = "sort_order", nullable = false)
    @lombok.Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    @lombok.Builder.Default
    private Boolean active = true;

    /** KASHI for own-build, SCF if an external catalogue is imported later. */
    @Column(name = "source", nullable = false, length = 20)
    @lombok.Builder.Default
    private String source = "KASHI";

    /** NULL = global. */
    @Column(name = "tenant_id")
    private Long tenantId;

    public enum NodeLevel {
        DOMAIN,
        FAMILY,
        CONTROL
    }

    /** Only leaves should be selectable in the library tag picker. */
    @Transient
    public boolean isSelectable() {
        return nodeLevel == NodeLevel.CONTROL && Boolean.TRUE.equals(active);
    }
}