package com.kashi.grc.audit.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * A saved snapshot of a policy's body, taken WITHIN one version.
 *
 * ── VERSIONS AND REVISIONS ARE DIFFERENT THINGS ──────────────────────────────
 * A VERSION is a policy row: approving v1 and starting v2 creates a new
 * audit_policies row linked by previous_version_id. That chain is the audit
 * evidence and is untouched by this table.
 *
 * A REVISION is a draft snapshot of one row's content_body: "r3 of v2". Before
 * this table, a draft had exactly one body; regenerating it with AI, or a
 * colleague's heavy edit, simply overwrote the previous text with no way back
 * and nothing to compare against.
 *
 * ── WHEN ONE IS TAKEN ────────────────────────────────────────────────────────
 *   MANUAL        the author clicked Save draft (autosave does not snapshot —
 *                 a revision every 30 s is noise, not history)
 *   BEFORE_AI     the body the editor held just before an AI draft replaced it
 *   AI_DRAFT      the AI's draft, once accepted into the editor and saved
 *   BEFORE_RESTORE / RESTORED   either side of restoring an older revision
 *
 * Identical consecutive content is never stored twice (content_hash), so
 * clicking Save draft on unchanged text costs nothing.
 */
@Entity
@Table(name = "audit_policy_revisions",
        uniqueConstraints = @UniqueConstraint(name = "uk_apr_policy_revision",
                columnNames = {"policy_id", "revision_no"}),
        indexes = {
                @Index(name = "idx_apr_policy", columnList = "policy_id"),
                @Index(name = "idx_apr_tenant", columnList = "tenant_id")
        })
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AuditPolicyRevision extends GlobalOrTenantEntity {

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    /** 1, 2, 3 … per policy row. */
    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo;

    /** The policy row's version when this was taken — "r3 of v2". */
    @Column(name = "policy_version")
    private Integer policyVersion;

    /** MANUAL | BEFORE_AI | AI_DRAFT | BEFORE_RESTORE | RESTORED */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "content_body", columnDefinition = "LONGTEXT")
    private String contentBody;

    /** SHA-256 of content_body — skips storing an identical consecutive snapshot. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "word_count")
    private Integer wordCount;

    /** For AI_DRAFT: the ai_interactions row that produced it. */
    @Column(name = "ai_interaction_id")
    private Long aiInteractionId;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_by")
    private Long createdBy;
}
