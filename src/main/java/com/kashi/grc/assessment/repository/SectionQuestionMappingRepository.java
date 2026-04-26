package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.SectionQuestionMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SectionQuestionMappingRepository extends JpaRepository<SectionQuestionMapping, Long> {

    List<SectionQuestionMapping> findBySectionIdOrderByOrderNo(Long sectionId);

    // Bulk variant — loads all question mappings for multiple sections in one query.
    // Used by ExecuteAssessmentAction to replace N individual findBySectionId calls.
    List<SectionQuestionMapping> findBySectionIdInOrderByOrderNo(java.util.Collection<Long> sectionIds);

    List<SectionQuestionMapping> findByQuestionId(Long questionId);

    Optional<SectionQuestionMapping> findBySectionIdAndQuestionId(Long sectionId, Long questionId);

    boolean existsBySectionIdAndQuestionId(Long sectionId, Long questionId);

    void deleteBySectionId(Long sectionId);

    void deleteBySectionIdAndQuestionId(Long sectionId, Long questionId);

    long countBySectionId(Long sectionId);

    void deleteByQuestionId(Long questionId);
}