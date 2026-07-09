package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditControlInstanceTestMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Projections live in AuditControlInstanceTestMappingRepositoryCustom,
 * implemented via the JPA Criteria API.
 */
@Repository
public interface AuditControlInstanceTestMappingRepository
        extends JpaRepository<AuditControlInstanceTestMapping, Long>,
        AuditControlInstanceTestMappingRepositoryCustom {

    List<AuditControlInstanceTestMapping> findByControlInstanceIdOrderByOrderNoAsc(Long controlInstanceId);

    List<AuditControlInstanceTestMapping> findByTestInstanceId(Long testInstanceId);

    List<AuditControlInstanceTestMapping> findByEngagementId(Long engagementId);

    void deleteByEngagementId(Long engagementId);
}
