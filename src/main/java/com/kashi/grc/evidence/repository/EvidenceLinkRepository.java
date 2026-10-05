package com.kashi.grc.evidence.repository;

import com.kashi.grc.evidence.domain.EvidenceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Evidence attached to a test instance OR to any control instance it is
     * mapped to. One count covers all three collection forms: manual uploads,
     * reused/auto-linked evidence and automated integration results are all rows
     * here - collection_type lives on evidence_records, not on the link.
     *
     * Pass a non-empty controlInstanceIds (use List.of(-1L) when there are none);
     * an empty IN list is invalid in JPQL.
     */
    @Query("""
           select count(el) from EvidenceLink el
           where el.tenantId = :tenantId
             and ( (el.targetEntityType = 'AUDIT_TEST_INSTANCE' and el.targetEntityId = :testInstanceId)
                or (el.targetEntityType = 'AUDIT_CONTROL_INSTANCE' and el.targetEntityId in :controlInstanceIds) )
           """)
    long countEvidenceForTest(@Param("testInstanceId") Long testInstanceId,
                              @Param("controlInstanceIds") List<Long> controlInstanceIds,
                              @Param("tenantId") Long tenantId);

    /** Mirror of {@link #countEvidenceForTest} anchored on the control instance. */
    @Query("""
           select count(el) from EvidenceLink el
           where el.tenantId = :tenantId
             and ( (el.targetEntityType = 'AUDIT_CONTROL_INSTANCE' and el.targetEntityId = :controlInstanceId)
                or (el.targetEntityType = 'AUDIT_TEST_INSTANCE' and el.targetEntityId in :testInstanceIds) )
           """)
    long countEvidenceForControl(@Param("controlInstanceId") Long controlInstanceId,
                                 @Param("testInstanceIds") List<Long> testInstanceIds,
                                 @Param("tenantId") Long tenantId);

    /**
     * Link counts for many targets in ONE query, as [targetEntityId, count] rows.
     * Used to badge the fieldwork list without reintroducing an N+1 per test row.
     */
    @Query("""
           select el.targetEntityId, count(el) from EvidenceLink el
           where el.tenantId = :tenantId
             and el.targetEntityType = :targetEntityType
             and el.targetEntityId in :targetEntityIds
           group by el.targetEntityId
           """)
    List<Object[]> countByTargetGrouped(@Param("targetEntityType") String targetEntityType,
                                        @Param("targetEntityIds") List<Long> targetEntityIds,
                                        @Param("tenantId") Long tenantId);
}