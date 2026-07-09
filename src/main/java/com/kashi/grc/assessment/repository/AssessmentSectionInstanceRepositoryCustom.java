package com.kashi.grc.assessment.repository;

import java.util.List;

/** Criteria API fragment for AssessmentSectionInstanceRepository. */
public interface AssessmentSectionInstanceRepositoryCustom {

    /** Distinct responder user IDs assigned a section under a template instance. */
    List<Long> findDistinctAssignedResponderIds(Long templateInstanceId);

    /** Distinct reviewer user IDs assigned a section under a template instance. */
    List<Long> findDistinctAssignedReviewerIds(Long templateInstanceId);
}
