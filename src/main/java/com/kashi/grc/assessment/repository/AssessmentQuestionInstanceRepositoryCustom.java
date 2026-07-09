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
}
