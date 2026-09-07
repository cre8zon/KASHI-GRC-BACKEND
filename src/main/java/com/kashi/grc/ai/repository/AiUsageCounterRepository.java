package com.kashi.grc.ai.repository;

import com.kashi.grc.ai.domain.AiUsageCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiUsageCounterRepository extends JpaRepository<AiUsageCounter, Long> {

    Optional<AiUsageCounter> findByTenantIdAndPeriodKey(Long tenantId, String periodKey);

    List<AiUsageCounter> findByPeriodKey(String periodKey);

    /**
     * Atomic increment. Deliberately native SQL, deliberately not read-modify-write.
     *
     * Two policy drafts running concurrently for one tenant would otherwise lose
     * an increment, and the lost one is always the expensive call. Optimistic
     * locking would technically be correct but converts a very hot row into a
     * retry storm; a single UPDATE ... SET x = x + ? is both correct and cheap,
     * and the row is a counter with no business invariants to protect.
     */
    @Modifying
    @Query(value = """
           UPDATE ai_usage_counters
              SET prompt_tokens     = prompt_tokens     + :prompt,
                  completion_tokens = completion_tokens + :completion,
                  total_tokens      = total_tokens      + :total,
                  embedding_tokens  = embedding_tokens  + :embedding,
                  request_count     = request_count     + 1,
                  failed_count      = failed_count      + :failed,
                  blocked_count     = blocked_count     + :blocked,
                  cost_micros       = cost_micros       + :cost,
                  updated_at        = CURRENT_TIMESTAMP(6)
            WHERE tenant_id = :tenantId AND period_key = :period
           """, nativeQuery = true)
    int increment(@Param("tenantId") Long tenantId,
                  @Param("period") String period,
                  @Param("prompt") long promptTokens,
                  @Param("completion") long completionTokens,
                  @Param("total") long totalTokens,
                  @Param("embedding") long embeddingTokens,
                  @Param("failed") int failed,
                  @Param("blocked") int blocked,
                  @Param("cost") long costMicros);

    /**
     * Create-if-absent without a race. INSERT IGNORE rather than a
     * select-then-insert: two concurrent first-calls of the month would
     * otherwise both see nothing and both insert, and the unique constraint
     * would turn a routine event into a 500 for one of them.
     */
    @Modifying
    @Query(value = """
           INSERT IGNORE INTO ai_usage_counters
             (tenant_id, period_key, prompt_tokens, completion_tokens, total_tokens,
              embedding_tokens, request_count, failed_count, blocked_count, cost_micros,
              warn_notified, limit_reached, created_at)
           VALUES (:tenantId, :period, 0,0,0,0,0,0,0,0, false, false, CURRENT_TIMESTAMP(6))
           """, nativeQuery = true)
    int ensureExists(@Param("tenantId") Long tenantId, @Param("period") String period);

    @Modifying
    @Query("update AiUsageCounter c set c.warnNotified = true where c.id = :id")
    int markWarned(@Param("id") Long id);

    @Modifying
    @Query("update AiUsageCounter c set c.limitReached = :reached where c.id = :id")
    int markLimit(@Param("id") Long id, @Param("reached") boolean reached);
}
