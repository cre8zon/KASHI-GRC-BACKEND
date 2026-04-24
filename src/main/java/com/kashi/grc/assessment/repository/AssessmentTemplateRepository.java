package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AssessmentTemplateRepository
        extends JpaRepository<AssessmentTemplate, Long> {

    /**
     * Finds a template accessible by this caller.
     * Handles both global templates (tenantId=null) and org-specific ones.
     * Used when an org user requests a specific template by ID.
     */
    Optional<AssessmentTemplate> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByNameAndTenantId(String name, Long tenantId);

    /** Dedup check for global templates (tenant_id IS NULL) */
    Optional<AssessmentTemplate> findByNameAndTenantIdIsNull(String name);
}