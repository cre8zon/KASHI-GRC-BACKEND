package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentQuestionOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AssessmentQuestionOptionRepository
        extends JpaRepository<AssessmentQuestionOption, Long> {

    /**
     * Used by CsvImportService find-or-create: look for an existing global option
     * (tenantId=null) with this exact value AND score before creating a new one.
     *
     * "Yes — certified" scoring 10 is a different library entry from "Yes — certified"
     * scoring 0, so both fields are required for the match.
     */
    Optional<AssessmentQuestionOption> findByOptionValueAndScoreAndTenantIdIsNull(
            String optionValue, Double score);

    /**
     * Variant for options with no score (score IS NULL).
     * Required because SQL NULL != NULL, so score=null cannot use the above method.
     */
    Optional<AssessmentQuestionOption> findByOptionValueAndScoreIsNullAndTenantIdIsNull(
            String optionValue);
}