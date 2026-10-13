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

    /**
     * Every plan entry built from this template.
     *
     * deleteTemplate used to do findAll().stream().filter(...), which loads the
     * whole table to find a handful of rows, and it needs the rows anyway to
     * tell a started plan entry from an unstarted one: engagementId is set once
     * an engagement has been created from the entry.
     */
    List<AuditProjectTemplate> findByTemplateId(Long templateId);

    @Transactional
    void deleteByProjectIdAndTemplateId(Long projectId, Long templateId);
}