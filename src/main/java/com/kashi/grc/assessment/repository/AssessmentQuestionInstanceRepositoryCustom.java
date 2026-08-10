package com.kashi.grc.assessment.repository;

import java.util.List;
import java.util.Map;

/** Criteria API fragment for AssessmentQuestionInstanceRepository. */
public interface AssessmentQuestionInstanceRepositoryCustom {

    /**
     * Total question weight for an assessment; NULL weight counts as 1.0
     * (former COALESCE(SUM(CASE WHEN ... THEN weight ELSE 1.0 END), 0.0)).
     */
    Double sumWeightByAssessmentId(Long assessmentId);

    /**
     * Question instances tenant-wide carrying a questionTagSnapshot.
     * Returns [{id, assignedUserId}] maps — same shape as the former
     * JPQL "SELECT new map(...)" — for EvidenceReuseEngine.propagate().
     */
    List<Map<String, Object>> findByTenantIdAndQuestionTagSnapshot(Long tenantId, String tag);

    /**
     * Question count per assessment, for a set of assessments — one GROUP BY
     * instead of countByAssessmentId once per row in a list view.
     * Returns rows of [assessmentId, count].
     */
    List<Object[]> countByAssessmentIdIn(java.util.Collection<Long> assessmentIds);

    /**
     * Total question weight per assessment, for a set of assessments. NULL weight
     * counts as 1.0, same rule as sumWeightByAssessmentId.
     * Returns rows of [assessmentId, weightSum].
     */
    List<Object[]> sumWeightByAssessmentIdIn(java.util.Collection<Long> assessmentIds);
}