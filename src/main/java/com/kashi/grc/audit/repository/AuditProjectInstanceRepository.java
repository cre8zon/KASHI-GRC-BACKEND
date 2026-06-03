package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditProjectInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface AuditProjectInstanceRepository extends JpaRepository<AuditProjectInstance, Long> {

    /**
     * Find existing project instance by originalProjectId.
     * Called by AuditEngagementService before creating a new engagement —
     * if instance exists, reuse it; if not, snapshot the project now.
     */
    Optional<AuditProjectInstance> findByOriginalProjectId(Long originalProjectId);

    boolean existsByOriginalProjectId(Long originalProjectId);
}