package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssessmentSectionRepository
        extends JpaRepository<AssessmentSection, Long> {
    // All queries via DbRepository (Criteria API)
    // No template_id FK exists anymore — use TemplateSectionMappingRepository

    /** Dedup check for global sections (tenant_id IS NULL) */
    Optional<AssessmentSection> findByNameAndTenantIdIsNull(String name);

    /**
     * Bulk fetch by IDs — single IN query replacing N individual findById calls.
     * Used by AssessmentTemplateController.getFull() to load all sections for a
     * template in one round-trip instead of one query per section.
     */
    @Query("SELECT s FROM AssessmentSection s WHERE s.id IN :ids")
    List<AssessmentSection> findAllByIdIn(@Param("ids") Collection<Long> ids);
}
