package com.kashi.grc.document.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * DocumentLink — polymorphic join table linking Documents to any entity.
 *
 * Replaces: vendor_documents, response_documents
 * Also handles: assessment reports, audit evidence, policy attachments, etc.
 *
 * Pattern is identical to action_items (entity_type + entity_id).
 *
 * link_type values:
 *   ATTACHMENT  — user-uploaded evidence attached to this entity
 *   REPORT      — system-generated report for this entity (assessment report v1/v2/v3)
 *   REFERENCE   — document reused from another context (same doc, different entity)
 *   TEMPLATE    — document is a form/template driving this entity
 *
 * entity_type values (extensible — just strings):
 *   VENDOR | ASSESSMENT | QUESTION_RESPONSE | REMEDIATION_ITEM |
 *   ACTION_ITEM | CONTROL | RISK | AUDIT | POLICY | WORKFLOW_INSTANCE
 *
 * Example queries:
 *   All evidence for a question response:
 *     WHERE entity_type='QUESTION_RESPONSE' AND entity_id=:qiId AND link_type='ATTACHMENT'
 *
 *   All report versions for an assessment:
 *     WHERE entity_type='ASSESSMENT' AND entity_id=:assessmentId AND link_type='REPORT'
 *     ORDER BY documents.version DESC
 *
 *   All documents for a vendor (any type):
 *     WHERE entity_type='VENDOR' AND entity_id=:vendorId
 */
@Entity
@Table(name = "document_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_document_link",
                columnNames = {"document_id", "entity_type", "entity_id", "link_type"}),
        indexes = {
                @Index(name = "idx_dl_entity",   columnList = "entity_type, entity_id, link_type"),
                @Index(name = "idx_dl_document", columnList = "document_id"),
                @Index(name = "idx_dl_tenant",   columnList = "tenant_id, entity_type"),
        })
@Getter @Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** ATTACHMENT | REPORT | REFERENCE | TEMPLATE */
    @Column(name = "link_type", nullable = false, length = 30)
    @Builder.Default
    private String linkType = "ATTACHMENT";

    /** For ordering multiple attachments on the same entity */
    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private java.time.LocalDateTime createdAt = LocalDateTime.now();

    /** Optional reviewer note about why this evidence satisfies a requirement */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}