package com.kashi.grc.evidence.repository;

import com.kashi.grc.evidence.domain.EvidenceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** countActiveByTenantAndTag lives in the Custom fragment (Criteria API). */
@Repository
public interface EvidenceRecordRepository extends JpaRepository<EvidenceRecord, Long>,
        JpaSpecificationExecutor<EvidenceRecord>,
        EvidenceRecordRepositoryCustom {

    List<EvidenceRecord> findByTenantIdAndControlTag(Long tenantId, String controlTag);

    List<EvidenceRecord> findByTenantId(Long tenantId);

    List<EvidenceRecord> findByExpiredFalseAndValidUntilBefore(LocalDateTime cutoff);
}
