package com.kashi.grc.assessment.repository;
import com.kashi.grc.assessment.domain.AssessmentSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssessmentSectionRepository
        extends JpaRepository<AssessmentSection, Long> {

    Optional<AssessmentSection> findByNameAndTenantIdIsNull(String name);

    /** Bulk fetch by IDs — derived query, no @Query needed. */
    List<AssessmentSection> findAllByIdIn(Collection<Long> ids);
}
