package com.kashi.grc.ai.domain;

import com.kashi.grc.ai.domain.AiEnums.FeedbackDecision;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AiSuggestionFeedback — what the human actually did with each suggestion.
 *
 * ── THIS IS THE ASSET, NOT THE CODE ──────────────────────────────────────────
 * Every mature AI product's advantage is a corpus of human judgements on machine
 * output. Vanta can say which control mappings its users accept because it has
 * watched sixteen thousand customers accept and reject them. That data cannot be
 * bought, cannot be scraped and — critically — CANNOT BE BACKFILLED. If you ship
 * suggestions for six months without recording the verdicts, those six months
 * are gone.
 *
 * So this table exists before you have the volume to justify it. It is cheap now
 * and impossible later.
 *
 * ── WHAT IT BUYS YOU, IN ORDER OF WHEN ───────────────────────────────────────
 *   Week 1   Acceptance rate per task type — the only honest quality metric, and
 *            the number an investor will ask for.
 *   Month 1  Per-prompt-version comparison: did v4 beat v3, or did it just feel
 *            better to whoever wrote it.
 *   Month 3  Few-shot mining: the highest-rated accepted outputs become examples
 *            injected into the prompt. Free quality, no fine-tuning.
 *   Month 6+ Preference pairs (rejected, accepted-edit) for fine-tuning or a
 *            reranker, if the volume ever justifies it.
 *
 * ── WHY EDIT DISTANCE ────────────────────────────────────────────────────────
 * ACCEPTED_WITH_EDIT covers everything from a comma to a total rewrite. Storing
 * the ratio turns that into a gradient: 0.02 means the model was right and the
 * user was fussy; 0.7 means the model was wrong and the user was polite.
 */
@Entity
@Table(name = "ai_suggestion_feedback",
        indexes = {
                @Index(name = "idx_asf_tenant",      columnList = "tenant_id"),
                @Index(name = "idx_asf_interaction", columnList = "interaction_id"),
                @Index(name = "idx_asf_task",        columnList = "task_type"),
                @Index(name = "idx_asf_decision",    columnList = "decision"),
                @Index(name = "idx_asf_template",    columnList = "prompt_template_key,prompt_template_version"),
                @Index(name = "idx_asf_entity",      columnList = "entity_type,entity_id")
        }
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AiSuggestionFeedback extends GlobalOrTenantEntity {

    /** The ai_interactions row that produced the suggestion. Zero-FK by convention. */
    @Column(name = "interaction_id", nullable = false)
    private Long interactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 40)
    private TaskType taskType;

    /**
     * Denormalised from the interaction so quality can be sliced by prompt
     * version without a join. This table is read analytically far more than it
     * is written, and the interaction row is immutable, so the copy cannot drift.
     */
    @Column(name = "prompt_template_key", length = 100)
    private String promptTemplateKey;

    @Column(name = "prompt_template_version")
    private Integer promptTemplateVersion;

    @Column(name = "model", length = 120)
    private String model;

    // ── Which suggestion ──────────────────────────────────────────────────────

    /**
     * Discriminator when one interaction yields many independently-judged items:
     * "CONTROL_MAPPING", "SECTION", "CLAUSE". A mapping call suggesting six
     * controls produces six rows — the user accepted four of them, and an
     * aggregate verdict would throw that away.
     */
    @Column(name = "suggestion_type", length = 60)
    private String suggestionType;

    /** Identifies the item inside the response: control code, section heading, array index. */
    @Column(name = "suggestion_key", length = 200)
    private String suggestionKey;

    // ── The verdict ───────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 30)
    private FeedbackDecision decision;

    @Column(name = "original_value", columnDefinition = "LONGTEXT")
    private String originalValue;

    /** What the human ended up with. Null unless the decision was ACCEPTED_WITH_EDIT. */
    @Column(name = "final_value", columnDefinition = "LONGTEXT")
    private String finalValue;

    /** Normalised Levenshtein distance 0.0–1.0. See AiFeedbackService.editRatio. */
    @Column(name = "edit_distance_ratio")
    private Double editDistanceRatio;

    /** Optional structured reason from a picker: WRONG_CONTROL, TOO_GENERIC, HALLUCINATED. */
    @Column(name = "reason_code", length = 60)
    private String reasonCode;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    /** Optional 1–5 rating when the surface offers one. */
    @Column(name = "rating")
    private Integer rating;

    // ── Context ───────────────────────────────────────────────────────────────

    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "user_id")
    private Long userId;

    /** Seconds between the suggestion rendering and the verdict. An instant reject reads differently from a considered one. */
    @Column(name = "time_to_decide_seconds")
    private Integer timeToDecideSeconds;

    /**
     * Marks rows cleared for use as few-shot examples or training data. Set
     * deliberately by an admin, never automatically: a customer's policy text is
     * theirs, and "we trained on your data" is a sentence that ends GRC deals.
     */
    @Column(name = "usable_as_example", nullable = false)
    @Builder.Default
    private Boolean usableAsExample = false;
}
