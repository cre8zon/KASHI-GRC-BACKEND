package com.kashi.grc.assessment.repository;

/** Criteria API fragment for ContributorSectionSubmissionRepository. */
public interface ContributorSectionSubmissionRepositoryCustom {

    /**
     * Distinct sections in an assessment where the user has question assignments
     * (COUNT(DISTINCT sectionInstanceId) over AssessmentQuestionInstance).
     */
    long countDistinctSectionsWithAssignments(Long assessmentId, Long contributorUserId);
}
