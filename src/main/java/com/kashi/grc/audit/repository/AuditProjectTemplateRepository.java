package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditProjectTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditProjectTemplateRepository extends JpaRepository<AuditProjectTemplate, Long> {

    List<AuditProjectTemplate> findByProjectIdOrderByOrderNoAsc(Long projectId);

    Optional<AuditProjectTemplate> findByProjectIdAndTemplateId(Long projectId, Long templateId);

    boolean existsByProjectIdAndTemplateId(Long projectId, Long templateId);

    @Transactional
    void deleteByProjectIdAndTemplateId(Long projectId, Long templateId);
}