package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.TemplateSectionMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TemplateSectionMappingRepository extends JpaRepository<TemplateSectionMapping, Long> {

    List<TemplateSectionMapping> findByTemplateIdOrderByOrderNo(Long templateId);

    List<TemplateSectionMapping> findBySectionId(Long sectionId);

    Optional<TemplateSectionMapping> findByTemplateIdAndSectionId(Long templateId, Long sectionId);

    boolean existsByTemplateIdAndSectionId(Long templateId, Long sectionId);

    void deleteByTemplateId(Long templateId);

    void deleteByTemplateIdAndSectionId(Long templateId, Long sectionId);

    long countByTemplateId(Long templateId);

    void deleteBySectionId(Long sectionId);
}