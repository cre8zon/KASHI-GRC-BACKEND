package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentTemplateInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AssessmentTemplateInstanceRepository
        extends JpaRepository<AssessmentTemplateInstance, Long> {

    Optional<AssessmentTemplateInstance> findByAssessmentId(Long assessmentId);

    /** Bulk variant for list views — one query instead of one per assessment row. */
    java.util.List<AssessmentTemplateInstance> findByAssessmentIdIn(
            java.util.Collection<Long> assessmentIds);
}