package com.kashi.grc.assessment.repository;

/** Criteria API fragment for ReviewerAssistantSectionSubmissionRepository. */
public interface ReviewerAssistantSectionSubmissionRepositoryCustom {

    /**
     * Distinct sections in an assessment where the user has question assignments
     * (COUNT(DISTINCT sectionInstanceId) over AssessmentQuestionInstance).
     */
    long countDistinctSectionsWithAssignments(Long assessmentId, Long assistantUserId);
}
