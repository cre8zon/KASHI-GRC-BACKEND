package com.kashi.grc.evidence.repository;

import com.kashi.grc.evidence.domain.EvidenceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Evidence-reuse and status queries live in the Custom fragment (Criteria API). */
@Repository
public interface EvidenceLinkRepository
        extends JpaRepository<EvidenceLink, Long>, EvidenceLinkRepositoryCustom {

    List<EvidenceLink> findByTargetEntityTypeAndTargetEntityIdAndTenantId(
            String targetEntityType, Long targetEntityId, Long tenantId);

    List<EvidenceLink> findByEvidenceRecordIdAndTenantId(Long evidenceRecordId, Long tenantId);

    Optional<EvidenceLink> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByEvidenceRecordIdAndTargetEntityTypeAndTargetEntityId(
            Long evidenceRecordId, String targetEntityType, Long targetEntityId);
}
