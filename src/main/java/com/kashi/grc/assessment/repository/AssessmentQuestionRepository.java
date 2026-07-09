package com.kashi.grc.assessment.repository;
import com.kashi.grc.assessment.domain.AssessmentQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssessmentQuestionRepository
        extends JpaRepository<AssessmentQuestion, Long> {

    Optional<AssessmentQuestion> findByQuestionTextAndResponseTypeAndTenantIdIsNull(
            String questionText, String responseType);

    long countByQuestionTag(String questionTag);

    /** Bulk fetch by IDs — the name alone derives "WHERE id IN :ids", no @Query needed. */
    List<AssessmentQuestion> findAllByIdIn(Collection<Long> ids);
}
