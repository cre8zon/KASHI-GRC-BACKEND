package com.kashi.grc.evidence.repository;

import com.kashi.grc.evidence.domain.EvidenceRecord;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EvidenceRecordRepository extends JpaRepository<EvidenceRecord, Long>,
        JpaSpecificationExecutor<EvidenceRecord> {

    List<EvidenceRecord> findByTenantIdAndControlTag(Long tenantId, String controlTag);

    List<EvidenceRecord> findByTenantId(Long tenantId);

    List<EvidenceRecord> findByExpiredFalseAndValidUntilBefore(LocalDateTime cutoff);

    @Query("""
        SELECT COUNT(e) FROM EvidenceRecord e
        WHERE e.tenantId = :tenantId AND e.controlTag = :tag AND e.expired = false
    """)
    long countActiveByTenantAndTag(@Param("tenantId") Long tenantId, @Param("tag") String tag);
}
