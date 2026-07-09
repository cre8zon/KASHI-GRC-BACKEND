package com.kashi.grc.assessment.repository;
import com.kashi.grc.assessment.domain.AssessmentQuestionOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssessmentQuestionOptionRepository
        extends JpaRepository<AssessmentQuestionOption, Long> {

    Optional<AssessmentQuestionOption> findByOptionValueAndScoreAndTenantIdIsNull(
            String optionValue, Double score);

    Optional<AssessmentQuestionOption> findByOptionValueAndScoreIsNullAndTenantIdIsNull(
            String optionValue);

    /** Bulk fetch by IDs — derived query, no @Query needed. */
    List<AssessmentQuestionOption> findAllByIdIn(Collection<Long> ids);
}
