package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditTemplateSectionMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List; import java.util.Optional;

@Repository
public interface AuditTemplateSectionMappingRepository extends JpaRepository<AuditTemplateSectionMapping, Long> {
    List<AuditTemplateSectionMapping> findByTemplateIdOrderByOrderNoAsc(Long templateId);
    Optional<AuditTemplateSectionMapping> findByTemplateIdAndSectionId(Long templateId, Long sectionId);
    @Transactional void deleteByTemplateIdAndSectionId(Long templateId, Long sectionId);
    @Transactional void deleteByTemplateId(Long templateId);
}
