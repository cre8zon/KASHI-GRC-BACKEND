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

    /**
     * Traceability query: given a test instance, find which control evidence
     * was used to evaluate it. Works because a test's evidence link and the
     * relevant control's evidence link share the same evidenceRecordId when
     * the auditor reused (linked, not re-uploaded) the control evidence file
     * when documenting the test result.
     *
     * Returns EvidenceLink rows targeting AUDIT_CONTROL_INSTANCE that share
     * an evidenceRecordId with any evidence linked to the given test.
     */
    @Query("""
        SELECT cl FROM EvidenceLink cl
        WHERE cl.targetEntityType = 'AUDIT_CONTROL_INSTANCE'
          AND cl.tenantId = :tenantId
          AND cl.evidenceRecordId IN (
              SELECT tl.evidenceRecordId FROM EvidenceLink tl
              WHERE tl.targetEntityType = 'AUDIT_TEST_INSTANCE'
                AND tl.targetEntityId = :testInstanceId
                AND tl.tenantId = :tenantId
          )
        ORDER BY cl.linkedAt
    """)
    List<EvidenceLink> findControlEvidenceUsedByTest(
            @Param("testInstanceId") Long testInstanceId,
            @Param("tenantId") Long tenantId);

    /**
     * Reverse traceability: given a control evidence link, find which tests
     * were evaluated using the same underlying evidence record.
     */
    @Query("""
        SELECT tl FROM EvidenceLink tl
        WHERE tl.targetEntityType = 'AUDIT_TEST_INSTANCE'
          AND tl.tenantId = :tenantId
          AND tl.evidenceRecordId = :evidenceRecordId
        ORDER BY tl.linkedAt
    """)
    List<EvidenceLink> findTestsUsingControlEvidence(
            @Param("evidenceRecordId") Long evidenceRecordId,
            @Param("tenantId") Long tenantId);
}