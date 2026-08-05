package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentOptionInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AssessmentOptionInstanceRepository
        extends JpaRepository<AssessmentOptionInstance, Long> {

    List<AssessmentOptionInstance> findByQuestionInstanceIdOrderByOrderNo(Long questionInstanceId);

    /**
     * Batch counterpart to findByQuestionInstanceIdOrderByOrderNo — ONE query
     * for every question in a section/assessment instead of one per question.
     * Fixes the last remaining per-question query in
     * AssessmentController.getMySections — everything else in that method
     * (questions, responses, user names, attachment counts) was already
     * bulk-loaded, but options were still fetched inside the per-question map.
     * Caller groups the flat list by questionInstanceId (e.g. via
     * Collectors.groupingBy) and should preserve orderNo when consuming —
     * this does not sort per-group the way the single-id query's derived
     * name does.
     */
    List<AssessmentOptionInstance> findByQuestionInstanceIdInOrderByOrderNo(Collection<Long> questionInstanceIds);

    /**
     * Maximum score any option can give for a question.
     * Used as the denominator when normalising scoreEarned at answer time:
     *
     *   normalisedScore = (selectedOption.score / maxOptionScore) × question.weight
     *
     * Returns null when no options exist or all options have null scores
     * (unscored question — caller treats as non-contributing).
     *
     * SINGLE_CHOICE: vendor picks one option → normalised against this max.
     * MULTI_CHOICE:  vendor picks N options  → normalised against SUM of all
     *                option scores (see sumScoreByQuestionInstanceId).
     */
    default Double maxScoreByQuestionInstanceId(Long questionInstanceId) {
        return findByQuestionInstanceIdOrderByOrderNo(questionInstanceId)
                .stream()
                .filter(o -> o.getScore() != null)
                .mapToDouble(AssessmentOptionInstance::getScore)
                .max()
                .orElse(0.0);
    }

    /**
     * Sum of all option scores for a question.
     * Used as the denominator when normalising MULTI_CHOICE responses:
     *
     *   normalisedScore = (sumSelectedScores / sumAllOptionScores) × weight
     *
     * This treats multi-choice as "what fraction of the total possible
     * option-score pool did the vendor select?" — the same model used by
     * ServiceNow GRC and OneTrust for multi-select scoring.
     *
     * Returns 0.0 when no options have scores configured.
     */
    default Double sumScoreByQuestionInstanceId(Long questionInstanceId) {
        return findByQuestionInstanceIdOrderByOrderNo(questionInstanceId)
                .stream()
                .filter(o -> o.getScore() != null)
                .mapToDouble(AssessmentOptionInstance::getScore)
                .sum();
    }
}