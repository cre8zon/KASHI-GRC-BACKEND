package com.kashi.grc.evidence.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * EvidenceLink — polymorphic join between one EvidenceRecord and one target entity.
 *
 * Status lifecycle:
 *
 *   MANUAL upload  → autoLinked=false → ACCEPTED immediately (human chose to link it)
 *   Tag auto-link  → autoLinked=true  → PENDING_REVIEW (human must accept/reject)
 *
 *   Automation PASS → autoLinked=true, AUTOMATION_VERIFIED (no human gate)
 *   Automation FAIL → autoLinked=true, PENDING_REVIEW (auditor documents exception)
 *
 *   Any link → EXPIRED when EvidenceRecord.validUntil passes (scheduled job)
 *
 * AUTOMATION_VERIFIED is the compliance signal for automated checks.
 * It means the integration confirmed the control is passing continuously.
 * Auditors can still override to REJECTED if they disagree with the automation result.
 */
@Entity
@Table(name = "evidence_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_evidence_link",
                columnNames = {"evidence_record_id", "target_entity_type", "target_entity_id"}
        ),
        indexes = {
                @Index(name = "idx_el_record",  columnList = "evidence_record_id"),
                @Index(name = "idx_el_target",  columnList = "target_entity_type, target_entity_id"),
                @Index(name = "idx_el_status",  columnList = "status"),
                @Index(name = "idx_el_tenant",  columnList = "tenant_id")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class EvidenceLink extends TenantAwareEntity {

    @Column(name = "evidence_record_id", nullable = false)
    private Long evidenceRecordId;

    // ── Target (polymorphic — zero FK) ────────────────────────────────────────

    @Column(name = "target_entity_type", nullable = false, length = 60)
    private String targetEntityType;

    @Column(name = "target_entity_id", nullable = false)
    private Long targetEntityId;

    // ── Status ────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    @Builder.Default
    private Status status = Status.PENDING_REVIEW;

    @Column(name = "auto_linked", nullable = false)
    @Builder.Default
    private boolean autoLinked = false;

    // ── Review ────────────────────────────────────────────────────────────────

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewer_note", columnDefinition = "TEXT")
    private String reviewerNote;

    // ── Snapshot at link time ─────────────────────────────────────────────────

    @Column(name = "matched_tag_snapshot", length = 80)
    private String matchedTagSnapshot;

    @Column(name = "linked_at", nullable = false)
    @Builder.Default
    private LocalDateTime linkedAt = LocalDateTime.now();

    @Column(name = "linked_by")
    private Long linkedBy;

    public enum Status {
        PENDING_REVIEW,       // auto-tag-linked; auditor must accept/reject
        ACCEPTED,             // human accepted — evidence satisfies this entity
        REJECTED,             // human rejected — evidence doesn't satisfy
        EXPIRED,              // underlying EvidenceRecord.validUntil passed
        AUTOMATION_VERIFIED   // integration confirmed PASS — no human gate needed
    }
}