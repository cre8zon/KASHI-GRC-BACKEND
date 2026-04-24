package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentTemplateInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AssessmentTemplateInstanceRepository
        extends JpaRepository<AssessmentTemplateInstance, Long> {

    Optional<AssessmentTemplateInstance> findByAssessmentId(Long assessmentId);
}