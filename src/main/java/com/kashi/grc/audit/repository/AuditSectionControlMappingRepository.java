package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditSectionControlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * The method name IS the query — Spring Data derives
     * "WHERE sectionId IN :ids ORDER BY sectionId, orderNo" from it, making the
     * former @Query annotation redundant.
     */
    List<AuditSectionControlMapping> findBySectionIdInOrderBySectionIdAscOrderNoAsc(
            Collection<Long> sectionIds);
}
