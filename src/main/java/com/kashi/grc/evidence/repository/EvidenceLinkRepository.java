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

    /**
     * Link counts per target AND status, as [targetEntityId, status, count] rows —
     * for the control cards' linked / accepted / verified evidence badges.
     */
    @Query("""
           select el.targetEntityId, el.status, count(el) from EvidenceLink el
           where el.tenantId = :tenantId
             and el.targetEntityType = :targetEntityType
             and el.targetEntityId in :targetEntityIds
           group by el.targetEntityId, el.status
           """)
    List<Object[]> countByTargetAndStatusGrouped(@Param("targetEntityType") String targetEntityType,
                                                 @Param("targetEntityIds") List<Long> targetEntityIds,
                                                 @Param("tenantId") Long tenantId);

    /**
     * Live link counts per target BY SOURCE, as [targetEntityId, integration, reused, failed]:
     *   integration — from an integration check (record collected AUTOMATED or
     *                 HYBRID, or the link arrived AUTOMATION_VERIFIED)
     *   reused      — pulled in by KashiLink from another control (autoLinked)
     *                 that is not integration evidence
     *   failed      — integration links still PENDING_REVIEW: a check that did
     *                 not pass and has not been reviewed or superseded
     * Live = pending review, accepted or verified (rejected / expired do not count).
     * For the control cards' source badges, which stay after submission.
     */
    @Query("""
           select el.targetEntityId,
                  sum(case when er.collectionType <> :manual or el.status = :verified then 1 else 0 end),
                  sum(case when el.autoLinked = true and er.collectionType = :manual and el.status <> :verified then 1 else 0 end),
                  sum(case when er.collectionType <> :manual and el.status = :pending then 1 else 0 end)
           from EvidenceLink el, EvidenceRecord er
           where er.id = el.evidenceRecordId
             and el.tenantId = :tenantId
             and el.targetEntityType = :targetEntityType
             and el.targetEntityId in :targetEntityIds
             and el.status in :live
           group by el.targetEntityId
           """)
    List<Object[]> countBySourceGrouped(@Param("targetEntityType") String targetEntityType,
                                        @Param("targetEntityIds") List<Long> targetEntityIds,
                                        @Param("tenantId") Long tenantId,
                                        @Param("manual") com.kashi.grc.evidence.domain.EvidenceRecord.CollectionType manual,
                                        @Param("verified") EvidenceLink.Status verified,
                                        @Param("pending") EvidenceLink.Status pending,
                                        @Param("live") java.util.Collection<EvidenceLink.Status> live);

    /**
     * Reused (KashiLink) links still waiting for a review that reuse no longer
     * needs — auto-linked, PENDING_REVIEW, from a record that is not an
     * integration check (a failed check stays pending for its exception).
     */
    @Query("""
           select el from EvidenceLink el, EvidenceRecord er
           where er.id = el.evidenceRecordId
             and el.autoLinked = true
             and el.status = :pending
             and er.collectionType <> :automated
           """)
    List<EvidenceLink> findPendingReuse(@Param("pending") EvidenceLink.Status pending,
                                        @Param("automated") com.kashi.grc.evidence.domain.EvidenceRecord.CollectionType automated);

    /**
     * Unreviewed failures of one integration check on one target that an EARLIER
     * run produced — superseded by the run in recordId. Only the latest run of a
     * check speaks for the control now.
     */
    @Query("""
           select el from EvidenceLink el, EvidenceRecord er
           where er.id = el.evidenceRecordId
             and el.targetEntityType = :targetEntityType
             and el.targetEntityId = :targetEntityId
             and el.status = :pending
             and er.integrationKey = :integrationKey
             and er.id < :recordId
           """)
    List<EvidenceLink> findOlderPendingRuns(@Param("targetEntityType") String targetEntityType,
                                            @Param("targetEntityId") Long targetEntityId,
                                            @Param("integrationKey") String integrationKey,
                                            @Param("recordId") Long recordId,
                                            @Param("pending") EvidenceLink.Status pending);

    /**
     * Every unreviewed integration failure that a later run of the same check has
     * since reached on the same target — the catch-up form of findOlderPendingRuns.
     */
    @Query("""
           select el from EvidenceLink el, EvidenceRecord er
           where er.id = el.evidenceRecordId
             and el.status = :pending
             and er.integrationKey is not null
             and exists (select 1 from EvidenceLink el2, EvidenceRecord er2
                         where er2.id = el2.evidenceRecordId
                           and el2.targetEntityType = el.targetEntityType
                           and el2.targetEntityId = el.targetEntityId
                           and er2.integrationKey = er.integrationKey
                           and er2.id > er.id)
           """)
    List<EvidenceLink> findSupersededPendingRuns(@Param("pending") EvidenceLink.Status pending);
}
