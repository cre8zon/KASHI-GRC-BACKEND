package com.kashi.grc.questionnaire.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/** One question, its answer, and where that answer came from. */
@Entity
@Table(name = "inbound_questions",
       indexes = {
           @Index(name = "idx_iqq_questionnaire", columnList = "questionnaire_id,sort_order"),
           @Index(name = "idx_iqq_review",        columnList = "tenant_id,review_status"),
       })
@Getter @Setter
@SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class InboundQuestion extends TenantAwareEntity {

    @Column(name = "questionnaire_id", nullable = false)
    private Long questionnaireId;

    /**
     * Where it sat in the original file.
     *
     * Without this an export is a NEW document rather than their document
     * filled in, and procurement teams reject that.
     */
    @Column(name = "source_ref", length = 60) private String sourceRef;
    @Column(length = 200) private String section;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "question_text", columnDefinition = "TEXT", nullable = false)
    private String questionText;

    @Column(name = "answer_text", columnDefinition = "TEXT") private String answerText;
    @Column(name = "short_answer", length = 30)              private String shortAnswer;

    /**
     * MANUAL | LIBRARY | AI_DRAFTED | AI_FROM_LIBRARY
     *
     * A reviewer must see at a glance which answers a human wrote and which a
     * model did. Without it, thirty AI drafts and three human answers look
     * identical on screen and get approved at the same speed.
     */
    @Column(name = "answer_source", nullable = false, length = 20)
    @Builder.Default
    private String answerSource = "MANUAL";

    @Column(name = "library_answer_id") private Long libraryAnswerId;

    /** Shown, never acted on. A model's confidence in its own output is not
     *  evidence about our controls. */
    private Integer confidence;

    @Column(name = "citations_json", columnDefinition = "JSON") private String citationsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    @Builder.Default
    private ReviewStatus reviewStatus = ReviewStatus.PENDING;

    @Column(name = "reviewed_by") private Long reviewedBy;
    @Column(name = "reviewed_at") private LocalDateTime reviewedAt;
    @Column(name = "reviewer_note", columnDefinition = "TEXT") private String reviewerNote;

    /** NEEDS_INFO exists because the honest answer to some questions is "ask
     *  the infrastructure team", and a status that cannot say that gets faked
     *  with a guess. */
    public enum ReviewStatus { PENDING, DRAFTED, APPROVED, NEEDS_INFO }

    @Transient
    public boolean isAiWritten() {
        return "AI_DRAFTED".equals(answerSource) || "AI_FROM_LIBRARY".equals(answerSource);
    }
}
