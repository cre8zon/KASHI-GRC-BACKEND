package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AssessmentQuestionRepository
        extends JpaRepository<AssessmentQuestion, Long> {

    // No section_id FK on AssessmentQuestion — use SectionQuestionMappingRepository.

    /** Dedup check for global questions (tenant_id IS NULL) */
    Optional<AssessmentQuestion> findByQuestionTextAndResponseTypeAndTenantIdIsNull(
            String questionText, String responseType);

    /**
     * Count how many library questions carry a given tag.
     * Used by GuardRuleController.toResponse() to populate GuardRuleResponse.questionCount —
     * tells the admin how many questions a rule covers ("Covers 12 questions").
     * Null-safe: if tag is null, returns 0 (Spring Data handles IS NULL correctly).
     */
    long countByQuestionTag(String questionTag);
}