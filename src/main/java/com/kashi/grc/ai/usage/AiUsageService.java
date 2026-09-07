package com.kashi.grc.ai.usage;

import com.kashi.grc.ai.config.AiProperties;
import com.kashi.grc.ai.domain.AiUsageCounter;
import com.kashi.grc.ai.guardrail.GuardrailException;
import com.kashi.grc.ai.repository.AiInteractionRepository;
import com.kashi.grc.ai.repository.AiUsageCounterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Token accounting and the hard budget stop.
 *
 * ── WHY A BUDGET GUARD IS NOT OPTIONAL ───────────────────────────────────────
 * Three failure modes, all of which happen to somebody in the first month:
 *
 *   1. A pipeline bug loops. A self-critique step that never converges will
 *      cheerfully spend a four-figure sum overnight with nobody watching.
 *   2. A customer discovers the feature and regenerates every policy in their
 *      library fourteen times because it is free to them.
 *   3. Someone scripts your API.
 *
 * The per-run cap in AiProperties.Budget catches the first, the monthly per
 * tenant cap catches the second and third. Both are cheap; discovering you need
 * them retroactively is not.
 *
 * ── WHY THE COUNTER IS INCREMENTED IN ITS OWN TRANSACTION ────────────────────
 * REQUIRES_NEW throughout. If a policy-draft transaction rolls back after the
 * model call succeeded, the tokens were still spent and your provider will still
 * invoice them. Letting the counter roll back with the business transaction
 * means the ledger drifts downward exactly when things are going wrong, which is
 * the worst possible time for your spend figure to be optimistic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private final AiUsageCounterRepository counterRepository;
    private final AiInteractionRepository  interactionRepository;
    private final AiProperties             props;

    public static String currentPeriod() {
        return YearMonth.now(ZoneOffset.UTC).toString();   // "2026-08"
    }

    // ── Pre-flight ────────────────────────────────────────────────────────────

    /**
     * Called BEFORE every model call. Throws when the allowance is spent.
     *
     * Platform-level work (tenantId null — an admin authoring global templates)
     * is not metered against a tenant; it is your own cost and belongs in your
     * own reporting, not in a customer's quota.
     */
    @Transactional(readOnly = true)
    public void assertWithinBudget(Long tenantId) {
        if (tenantId == null) return;

        long limit = resolveLimit(tenantId);
        if (limit <= 0) return;                       // 0 = unlimited

        long used = counterRepository.findByTenantIdAndPeriodKey(tenantId, currentPeriod())
                .map(AiUsageCounter::getTotalTokens).orElse(0L);

        if (used >= limit) {
            log.warn("[AI-USAGE] tenant {} blocked — {}/{} tokens used this period", tenantId, used, limit);
            throw GuardrailException.budgetExceeded(used, limit);
        }
    }

    /** Remaining allowance, for the banner the UI shows before an expensive action. */
    @Transactional(readOnly = true)
    public long remainingTokens(Long tenantId) {
        if (tenantId == null) return Long.MAX_VALUE;
        long limit = resolveLimit(tenantId);
        if (limit <= 0) return Long.MAX_VALUE;
        long used = counterRepository.findByTenantIdAndPeriodKey(tenantId, currentPeriod())
                .map(AiUsageCounter::getTotalTokens).orElse(0L);
        return Math.max(0, limit - used);
    }

    private long resolveLimit(Long tenantId) {
        return counterRepository.findByTenantIdAndPeriodKey(tenantId, currentPeriod())
                .map(AiUsageCounter::getTokenLimitOverride)
                .filter(v -> v != null && v > 0)
                .orElse(props.getBudget().getMonthlyTokensPerTenant());
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    /**
     * Record spend. Never throws — a failure here must not fail the user's
     * request, because the work has already been done and the answer already
     * exists. A logged discrepancy is recoverable via reconcile(); a 500 served
     * on top of a successful generation is not.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long tenantId, int promptTokens, int completionTokens,
                       int embeddingTokens, long costMicros, boolean failed, boolean blocked) {
        if (tenantId == null) return;
        try {
            String period = currentPeriod();
            counterRepository.ensureExists(tenantId, period);
            counterRepository.increment(tenantId, period,
                    promptTokens, completionTokens, promptTokens + completionTokens,
                    embeddingTokens, failed ? 1 : 0, blocked ? 1 : 0, costMicros);
            checkThresholds(tenantId, period);
        } catch (Exception e) {
            log.error("[AI-USAGE] failed to record usage for tenant {} — ledger remains authoritative", tenantId, e);
        }
    }

    /** Fires the warn notification exactly once per period, and flips the limit flag. */
    private void checkThresholds(Long tenantId, String period) {
        counterRepository.findByTenantIdAndPeriodKey(tenantId, period).ifPresent(c -> {
            long limit = c.getTokenLimitOverride() != null && c.getTokenLimitOverride() > 0
                    ? c.getTokenLimitOverride() : props.getBudget().getMonthlyTokensPerTenant();
            if (limit <= 0) return;

            double ratio = (double) c.getTotalTokens() / limit;
            if (ratio >= props.getBudget().getWarnThreshold() && !Boolean.TRUE.equals(c.getWarnNotified())) {
                counterRepository.markWarned(c.getId());
                log.warn("[AI-USAGE] tenant {} crossed {}% of AI allowance ({}/{})",
                        tenantId, (int) (props.getBudget().getWarnThreshold() * 100), c.getTotalTokens(), limit);
                // Hook: publish a NotificationEvent here to surface the warning in-app.
            }
            boolean reached = c.getTotalTokens() >= limit;
            if (reached != Boolean.TRUE.equals(c.getLimitReached())) counterRepository.markLimit(c.getId(), reached);
        });
    }

    /**
     * Rebuild a period's counter from ai_interactions. The ledger is
     * authoritative; the counter is a cache with a fast path. Run this after any
     * incident that could have dropped increments.
     */
    @Transactional
    public long reconcile(Long tenantId, String periodKey) {
        YearMonth ym  = YearMonth.parse(periodKey);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to   = ym.plusMonths(1).atDay(1).atStartOfDay();

        long actual = interactionRepository.sumTokens(tenantId, from, to);
        counterRepository.ensureExists(tenantId, periodKey);

        counterRepository.findByTenantIdAndPeriodKey(tenantId, periodKey).ifPresent(c -> {
            if (c.getTotalTokens() != actual) {
                log.warn("[AI-USAGE] counter drift for tenant {} {}: counter={} ledger={} — correcting",
                        tenantId, periodKey, c.getTotalTokens(), actual);
                c.setTotalTokens(actual);
                counterRepository.save(c);
            }
        });
        return actual;
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> summary(Long tenantId) {
        String period = currentPeriod();
        Optional<AiUsageCounter> c = counterRepository.findByTenantIdAndPeriodKey(tenantId, period);
        long limit = resolveLimit(tenantId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period",        period);
        m.put("tokensUsed",    c.map(AiUsageCounter::getTotalTokens).orElse(0L));
        m.put("tokenLimit",    limit);
        m.put("remaining",     remainingTokens(tenantId));
        m.put("requests",      c.map(AiUsageCounter::getRequestCount).orElse(0L));
        m.put("failed",        c.map(AiUsageCounter::getFailedCount).orElse(0L));
        m.put("blocked",       c.map(AiUsageCounter::getBlockedCount).orElse(0L));
        m.put("costMicros",    c.map(AiUsageCounter::getCostMicros).orElse(0L));
        m.put("limitReached",  c.map(AiUsageCounter::getLimitReached).orElse(false));
        return m;
    }

    /** Cost in micro-units from the per-model rates in configuration. */
    public long computeCostMicros(String providerKey, int promptTokens, int completionTokens) {
        AiProperties.ProviderConfig p = providerKey == null ? null
                : props.getProviders().get(providerKey.toLowerCase());
        if (p == null) return 0L;   // unpriced provider: report zero rather than guess
        long in  = (long) promptTokens     * p.getInputCostPerMillionMicros()  / 1_000_000L;
        long out = (long) completionTokens * p.getOutputCostPerMillionMicros() / 1_000_000L;
        return in + out;
    }
}