package com.kashi.grc.usermanagement.repository;

import java.util.List;

/**
 * Criteria-backed queries for memberships.
 *
 * Same Custom/Impl split the audit repositories use (AuditPolicyRepositoryCustom
 * → AuditPolicyRepositoryImpl), so this needs no @Query annotation.
 */
public interface UserTenantMembershipRepositoryCustom {

    /**
     * The distinct audit firms with at least one ACTIVE, unexpired guest in this
     * tenant, with a count of their auditors.
     *
     * A client can invite several firms, and "external auditor" alone does not
     * say which one a person belongs to. Choosing from a flat list of every
     * guest is how someone from Firm A ends up on an engagement Firm B is
     * running — an access mistake rather than a typo.
     */
    List<AuditFirmSummary> findActiveFirmsForTenant(Long tenantId);

    /** Named accessors rather than positional Object[] casts. */
    record AuditFirmSummary(Long firmTenantId, String firmName, long auditorCount) {}
}