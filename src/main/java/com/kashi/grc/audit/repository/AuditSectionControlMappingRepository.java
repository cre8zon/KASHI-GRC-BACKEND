package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditSectionControlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditSectionControlMappingRepository
        extends JpaRepository<AuditSectionControlMapping, Long> {

    List<AuditSectionControlMapping> findBySectionIdOrderByOrderNoAsc(Long sectionId);

    Optional<AuditSectionControlMapping> findBySectionIdAndControlId(Long sectionId, Long controlId);

    /** Remove all mappings for a control — called before deleting a control from the library */
    @Transactional
    void deleteByControlId(Long controlId);

    /** Remove a specific section↔control mapping — called by removeControlFromSection */
    @Transactional
    void deleteBySectionIdAndControlId(Long sectionId, Long controlId);

    /**
     * Bulk-load all mappings for a set of section IDs in ONE query.
     * Used by getFullTemplate to avoid N+1 (one query per section node).
     */
    @Query("SELECT m FROM AuditSectionControlMapping m WHERE m.sectionId IN :sectionIds ORDER BY m.sectionId ASC, m.orderNo ASC")
    List<AuditSectionControlMapping> findBySectionIdInOrderBySectionIdAscOrderNoAsc(
            @Param("sectionIds") Collection<Long> sectionIds);
}