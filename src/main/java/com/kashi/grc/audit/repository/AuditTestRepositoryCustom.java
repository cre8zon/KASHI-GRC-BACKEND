package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditTest;
import java.util.List;

/** Criteria API fragment for AuditTestRepository. */
public interface AuditTestRepositoryCustom {

    /** Count tests visible to a tenant (global + tenant) — next AT-NNN ref. */
    long countForTenant(Long tenantId);

    /** Case-insensitive name contains-search across visible tests. */
    List<AuditTest> searchByName(Long tenantId, String search);

    /**
     * Summary projection for the list screen — selects columns explicitly so
     * test_procedure and evidence_guidance are never read. See AuditTestSummary.
     * Pass null search to list everything.
     */
    List<AuditTestSummary> findSummariesForTenant(Long tenantId, String search);
}