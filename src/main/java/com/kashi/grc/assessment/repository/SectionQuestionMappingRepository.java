package com.kashi.grc.assessment.repository;
import com.kashi.grc.assessment.domain.SectionQuestionMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** countQuestionsForTemplate lives in the Custom fragment (Criteria API). */
@Repository
public interface SectionQuestionMappingRepository
        extends JpaRepository<SectionQuestionMapping, Long>, SectionQuestionMappingRepositoryCustom {

    List<SectionQuestionMapping> findBySectionIdOrderByOrderNo(Long sectionId);

    List<SectionQuestionMapping> findBySectionIdInOrderByOrderNo(Collection<Long> sectionIds);

    List<SectionQuestionMapping> findByQuestionId(Long questionId);

    Optional<SectionQuestionMapping> findBySectionIdAndQuestionId(Long sectionId, Long questionId);

    boolean existsBySectionIdAndQuestionId(Long sectionId, Long questionId);

    void deleteBySectionId(Long sectionId);

    void deleteBySectionIdIn(Collection<Long> sectionIds);

    void deleteBySectionIdAndQuestionId(Long sectionId, Long questionId);

    long countBySectionId(Long sectionId);

    void deleteByQuestionId(Long questionId);

    void deleteByQuestionIdIn(Collection<Long> questionIds);
}
