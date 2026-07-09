package com.kashi.grc.evidence.repository;

/** Criteria API fragment for EvidenceRecordRepository. */
public interface EvidenceRecordRepositoryCustom {

    /** Count non-expired evidence records for a tenant + control tag. */
    long countActiveByTenantAndTag(Long tenantId, String tag);
}
