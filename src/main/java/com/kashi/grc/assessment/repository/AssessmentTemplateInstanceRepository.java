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

    /**
     * How many real assessments have been built from this library template.
     *
     * A snapshot row exists from the moment EXECUTE_ASSESSMENT instantiates a
     * template, so a non-zero count means deleting the template would remove
     * something live work was started from. Used by the template delete to
     * refuse rather than retire.
     */
    long countByOriginalTemplateId(Long originalTemplateId);
}