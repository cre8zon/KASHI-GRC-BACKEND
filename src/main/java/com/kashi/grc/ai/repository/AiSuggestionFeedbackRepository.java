package com.kashi.grc.ai.repository;

import com.kashi.grc.ai.domain.AiEnums.FeedbackDecision;
import com.kashi.grc.ai.domain.AiEnums.TaskType;
import com.kashi.grc.ai.domain.AiSuggestionFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AiSuggestionFeedbackRepository extends JpaRepository<AiSuggestionFeedback, Long> {

    List<AiSuggestionFeedback> findByInteractionId(Long interactionId);

    boolean existsByInteractionIdAndSuggestionKey(Long interactionId, String suggestionKey);

    /**
     * Acceptance rate per task type — the headline quality number, and the one
     * an investor will ask for by name. IGNORED is counted in the denominator on
     * purpose: a suggestion nobody engaged with is a failed suggestion, and
     * excluding it is the most common way these dashboards flatter themselves.
     */
    @Query("""
           select f.taskType,
                  count(f),
                  sum(case when f.decision in (:acceptedStatuses) then 1 else 0 end),
                  coalesce(avg(f.editDistanceRatio), 0)
           from AiSuggestionFeedback f
           where (:tenantId is null or f.tenantId = :tenantId)
             and f.createdAt >= :since
           group by f.taskType
           """)
    List<Object[]> acceptanceByTaskType(@Param("tenantId") Long tenantId,
                                        @Param("since") LocalDateTime since,
                                        @Param("acceptedStatuses") List<FeedbackDecision> acceptedStatuses);

    /**
     * Did the new prompt version actually beat the old one? Without this you are
     * changing prompts on vibes, which is the single most common failure mode in
     * shipped AI features.
     */
    @Query("""
           select f.promptTemplateKey, f.promptTemplateVersion,
                  count(f),
                  sum(case when f.decision = :accepted then 1 else 0 end),
                  coalesce(avg(f.editDistanceRatio), 0)
           from AiSuggestionFeedback f
           where f.promptTemplateKey = :key
           group by f.promptTemplateKey, f.promptTemplateVersion
           order by f.promptTemplateVersion desc
           """)
    List<Object[]> qualityByPromptVersion(@Param("key") String key,
                                          @Param("accepted") FeedbackDecision accepted);

    /**
     * Few-shot mining. Clean accepted outputs, consent flag set, newest first.
     * Free quality improvement with no fine-tuning and no vendor lock-in.
     */
    @Query("""
           select f from AiSuggestionFeedback f
           where f.taskType = :task
             and f.decision = :accepted
             and f.usableAsExample = true
             and (f.tenantId = :tenantId or f.tenantId is null)
           order by f.createdAt desc
           """)
    List<AiSuggestionFeedback> mineExamples(@Param("task") TaskType task,
                                            @Param("tenantId") Long tenantId,
                                            @Param("accepted") FeedbackDecision accepted);

    /** Preference pairs for later fine-tuning: the model was wanted but wrong as written. */
    @Query("""
           select f from AiSuggestionFeedback f
           where f.taskType = :task
             and f.decision = :acceptedWithEdit
             and f.editDistanceRatio >= :minRatio
             and f.usableAsExample = true
           order by f.editDistanceRatio desc
           """)
    List<AiSuggestionFeedback> minePreferencePairs(@Param("task") TaskType task,
                                                   @Param("minRatio") Double minRatio,
                                                   @Param("acceptedWithEdit") FeedbackDecision acceptedWithEdit);
}
