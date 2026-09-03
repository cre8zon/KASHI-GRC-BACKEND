package com.kashi.grc.ai.domain;

import com.kashi.grc.common.domain.GlobalOrTenantEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AiUsageCounter — one row per (tenant, period). Pre-aggregated so a budget
 * check is a primary-key read.
 *
 * ── WHY NOT SUM(ai_interactions) ─────────────────────────────────────────────
 * Because the budget is checked BEFORE every model call. A scan-and-sum over a
 * table that grows by a dozen rows per user action is a guaranteed hotspot, and
 * you are on Aiven with a couple hundred milliseconds of round-trip — a full
 * aggregate on the critical path would be felt immediately.
 *
 * The counter is the fast path; ai_interactions stays the authoritative ledger.
 * If they ever disagree, the ledger wins and AiUsageService.reconcile(period)
 * rewrites the counter from it.
 *
 * ── CONCURRENCY ──────────────────────────────────────────────────────────────
 * Increments are a single atomic UPDATE ... SET x = x + ? in the repository, not
 * read-modify-write in Java. Two policy drafts running concurrently for one
 * tenant must not lose an increment, and @Version optimistic locking would just
 * convert that into retries on a very hot row.
 *
 * ── PERIOD KEY ───────────────────────────────────────────────────────────────
 * "yyyy-MM" in UTC. Calendar-month billing is what customers understand, and
 * UTC avoids a tenant in Kolkata and one in California disagreeing about when
 * their allowance resets.
 */
@Entity
@Table(name = "ai_usage_counters",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_auc_tenant_period",
                columnNames = {"tenant_id", "period_key"}),
        indexes = @Index(name = "idx_auc_period", columnList = "period_key")
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AiUsageCounter extends GlobalOrTenantEntity {

    /** UTC calendar month, "yyyy-MM". */
    @Column(name = "period_key", nullable = false, length = 10)
    private String periodKey;

    @Column(name = "prompt_tokens",     nullable = false) @Builder.Default private Long promptTokens     = 0L;
    @Column(name = "completion_tokens", nullable = false) @Builder.Default private Long completionTokens = 0L;
    @Column(name = "total_tokens",      nullable = false) @Builder.Default private Long totalTokens      = 0L;
    @Column(name = "embedding_tokens",  nullable = false) @Builder.Default private Long embeddingTokens  = 0L;

    @Column(name = "request_count",     nullable = false) @Builder.Default private Long requestCount     = 0L;
    @Column(name = "failed_count",      nullable = false) @Builder.Default private Long failedCount      = 0L;
    @Column(name = "blocked_count",     nullable = false) @Builder.Default private Long blockedCount     = 0L;

    @Column(name = "cost_micros",       nullable = false) @Builder.Default private Long costMicros       = 0L;

    /**
     * Per-tenant override of the platform cap. Null = use AiProperties. Set on a
     * plan upgrade or when a customer buys an AI add-on, so commercial changes
     * do not require a redeploy.
     */
    @Column(name = "token_limit_override")
    private Long tokenLimitOverride;

    /** Set once the warn threshold is crossed, so the notification fires exactly once per period. */
    @Column(name = "warn_notified", nullable = false)
    @Builder.Default
    private Boolean warnNotified = false;

    /** Set once the hard cap is hit — drives the "AI paused" banner in the UI. */
    @Column(name = "limit_reached", nullable = false)
    @Builder.Default
    private Boolean limitReached = false;
}
