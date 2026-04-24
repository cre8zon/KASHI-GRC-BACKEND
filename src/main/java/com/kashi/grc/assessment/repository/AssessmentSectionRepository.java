package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AssessmentSectionRepository
        extends JpaRepository<AssessmentSection, Long> {
    // All queries via DbRepository (Criteria API)
    // No template_id FK exists anymore — use TemplateSectionMappingRepository

    /** Dedup check for global sections (tenant_id IS NULL) */
    Optional<AssessmentSection> findByNameAndTenantIdIsNull(String name);
}