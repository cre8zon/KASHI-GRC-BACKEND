package com.kashi.grc.evidence.repository;

import com.kashi.grc.evidence.domain.EvidenceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** countActiveByTenantAndTag lives in the Custom fragment (Criteria API). */
@Repository
public interface EvidenceRecordRepository extends JpaRepository<EvidenceRecord, Long>,
        JpaSpecificationExecutor<EvidenceRecord>,
        EvidenceRecordRepositoryCustom {

    List<EvidenceRecord> findByTenantIdAndControlTag(Long tenantId, String controlTag);

    List<EvidenceRecord> findByTenantId(Long tenantId);

    /**
     * Manual evidence stores its document id in fileUrl as a string
     * (EvidenceRegistrationService), so this is the join from a document back to
     * its evidence record. A derived query rather than filtering findByTenantId
     * in memory — a tenant can have thousands of records and the delete path
     * needs exactly one.
     */
    java.util.Optional<EvidenceRecord> findByTenantIdAndFileUrl(Long tenantId, String fileUrl);

    List<EvidenceRecord> findByExpiredFalseAndValidUntilBefore(LocalDateTime cutoff);

    // ── KashiLink ────────────────────────────────────────────────────────────

    /** Tenant-scoped read — plain findById leaked records across tenants. */
    Optional<EvidenceRecord> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * Look up the record created for a given document.
     * fileUrl holds the documentId as a string (see EvidenceRecordRequest javadoc),
     * so this is what makes EvidenceRegistrationService idempotent when the same
     * document is linked to a second entity.
     */
    Optional<EvidenceRecord> findFirstByTenantIdAndFileUrl(Long tenantId, String fileUrl);

    /**
     * Candidate evidence for the pull side of the engine: everything this tenant
     * already holds under any of the given tags, excluding expired records.
     * Used by AuditEvidenceBackfillService when a new engagement is instantiated.
     */
    List<EvidenceRecord> findByTenantIdAndControlTagInAndExpiredFalse(
            Long tenantId, java.util.Collection<String> controlTags);
}