package com.kashi.grc.ai.repository;

import com.kashi.grc.ai.domain.AiEnums.InteractionStatus;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.domain.AiInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AiInteractionRepository extends JpaRepository<AiInteraction, Long> {

    List<AiInteraction> findByCorrelationIdOrderByStepIndexAsc(String correlationId);

    List<AiInteraction> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);

    /**
     * Cache lookup for an identical generation. Same template version, same
     * model, same inputs -> the previous answer is still the right answer, and
     * re-billing the customer for it is indefensible.
     *
     * Bounded by `since` so a genuinely stale hit is not served forever, and
     * restricted to SUCCESS so a cached failure is never replayed.
     */
    @Query("""
           select i from AiInteraction i
           where i.inputHash = :hash
             and i.status = :successStatus
             and i.evalRun = false
             and (:tenantId is null and i.tenantId is null or i.tenantId = :tenantId)
             and i.createdAt >= :since
           order by i.createdAt desc
           """)
    List<AiInteraction> findCacheable(@Param("hash") String hash,
                                      @Param("tenantId") Long tenantId,
                                      @Param("since") LocalDateTime since,
                                      @Param("successStatus") InteractionStatus successStatus);

    /** Powers the per-tenant usage screen and the reconcile sweep. */
    @Query("""
           select coalesce(sum(i.totalTokens), 0) from AiInteraction i
           where i.tenantId = :tenantId
             and i.evalRun = false
             and i.createdAt >= :from and i.createdAt < :to
           """)
    long sumTokens(@Param("tenantId") Long tenantId,
                   @Param("from") LocalDateTime from,
                   @Param("to") LocalDateTime to);

    @Query("""
           select i.taskType, count(i), coalesce(sum(i.totalTokens),0), coalesce(avg(i.latencyMs),0)
           from AiInteraction i
           where (:tenantId is null or i.tenantId = :tenantId)
             and i.createdAt >= :from
             and i.evalRun = false
           group by i.taskType
           """)
    List<Object[]> statsByTaskType(@Param("tenantId") Long tenantId,
                                   @Param("from") LocalDateTime from);

    List<AiInteraction> findTop50ByTaskTypeAndTenantIdOrderByCreatedAtDesc(TaskType taskType, Long tenantId);
}
