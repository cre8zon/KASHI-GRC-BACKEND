package com.kashi.grc.questionnaire.domain;

import com.kashi.grc.common.domain.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * A curated, human-approved answer, reused across questionnaires.
 *
 * ── THIS IS THE ASSET, NOT THE MODEL ──────────────────────────────────────
 * Pointing an LLM at the policy corpus produces fluent, plausible answers about
 * controls the organisation may not have — and every one is a contractual
 * representation to a customer. What makes this module safe is a library a
 * human approved, with the model MATCHING questions to it rather than inventing
 * prose. The corpus is the fallback, not the source.
 *
 * ── AND A STALE ENTRY IS WORSE THAN NO ENTRY ──────────────────────────────
 * An answer used forty times, which stopped being true six months ago, is
 * confidently wrong: it carries the authority of having been approved once, and
 * nobody re-reads a suggestion that matches. Hence reviewDueAt and usageCount,
 * both surfaced. Same reasoning as exception expiry.
 */
@Entity
@Table(name = "answer_library",
       indexes = {
           @Index(name = "idx_al_tenant", columnList = "tenant_id,status,is_deleted"),
           @Index(name = "idx_al_review", columnList = "tenant_id,status,review_due_at"),
       })
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AnswerLibraryEntry extends AuditableEntity {

    /** The canonical question, as we would phrase it. Matched semantically —
     *  the same question arrives worded forty different ways. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String answer;

    /** YES | NO | PARTIAL | NOT_APPLICABLE — many questionnaires want a
     *  verdict alongside the prose. */
    @Column(name = "short_answer", length = 30)
    private String shortAnswer;

    @Column(length = 80)  private String category;
    @Column(name = "control_tags", length = 500)   private String controlTags;
    @Column(name = "framework_refs", length = 300) private String frameworkRefs;

    /** An answer with no evidence behind it is an assertion, and the reviewer
     *  should be able to see which is which. */
    @Column(name = "evidence_document_id") private Long evidenceDocumentId;

    /** DRAFT | APPROVED | RETIRED. Only APPROVED is ever suggested. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.DRAFT;

    @Column(name = "approved_by") private Long approvedBy;
    @Column(name = "approved_at") private LocalDateTime approvedAt;

    /** Surfaced deliberately: an answer reused forty times deserves MORE
     *  scrutiny than one used twice, not less. */
    @Column(name = "usage_count", nullable = false)
    @Builder.Default
    private Integer usageCount = 0;

    @Column(name = "last_used_at")  private LocalDateTime lastUsedAt;
    @Column(name = "review_due_at") private LocalDateTime reviewDueAt;

    public enum Status { DRAFT, APPROVED, RETIRED }

    @Transient
    public boolean isSuggestable() { return status == Status.APPROVED; }

    @Transient
    public boolean isStale() {
        return status == Status.APPROVED
                && reviewDueAt != null
                && reviewDueAt.isBefore(LocalDateTime.now());
    }
}
