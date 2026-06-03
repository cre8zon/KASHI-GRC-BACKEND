package com.kashi.grc.evidence.repository;

import com.kashi.grc.evidence.domain.EvidenceLink;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EvidenceLinkRepository extends JpaRepository<EvidenceLink, Long> {

    List<EvidenceLink> findByTargetEntityTypeAndTargetEntityIdAndTenantId(
        String targetEntityType, Long targetEntityId, Long tenantId);

    List<EvidenceLink> findByEvidenceRecordIdAndTenantId(Long evidenceRecordId, Long tenantId);

    Optional<EvidenceLink> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByEvidenceRecordIdAndTargetEntityTypeAndTargetEntityId(
        Long evidenceRecordId, String targetEntityType, Long targetEntityId);

    @Modifying
    @Query("""
        UPDATE EvidenceLink l SET l.status = 'EXPIRED'
        WHERE l.evidenceRecordId = :recordId
          AND l.status IN ('PENDING_REVIEW', 'ACCEPTED')
    """)
    int expireByEvidenceRecordId(@Param("recordId") Long recordId);

    @Query("""
        SELECT l FROM EvidenceLink l
        WHERE l.tenantId = :tenantId
          AND l.status = 'PENDING_REVIEW'
          AND l.autoLinked = true
        ORDER BY l.linkedAt DESC
    """)
    List<EvidenceLink> findPendingReviewForTenant(@Param("tenantId") Long tenantId);

    @Query("""
        SELECT COUNT(l) FROM EvidenceLink l
        WHERE l.targetEntityType = :entityType
          AND l.targetEntityId = :entityId
          AND l.status = 'ACCEPTED'
    """)
    long countAcceptedForEntity(@Param("entityType") String entityType,
                                @Param("entityId") Long entityId);
}
