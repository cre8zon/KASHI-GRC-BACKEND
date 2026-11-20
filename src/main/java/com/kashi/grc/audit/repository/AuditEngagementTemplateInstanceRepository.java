package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditEngagementTemplateInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface AuditEngagementTemplateInstanceRepository extends JpaRepository<AuditEngagementTemplateInstance, Long> {
    Optional<AuditEngagementTemplateInstance> findByEngagementId(Long engagementId);

    /** Whether the engagement's template has already been snapshotted (retry guard). */
    boolean existsByEngagementId(Long engagementId);
}