package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditPolicyControlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Projection lives in AuditPolicyControlMappingRepositoryCustom (Criteria API). */
@Repository
public interface AuditPolicyControlMappingRepository
        extends JpaRepository<AuditPolicyControlMapping, Long>,
        AuditPolicyControlMappingRepositoryCustom {

    List<AuditPolicyControlMapping> findByPolicyId(Long policyId);

    List<AuditPolicyControlMapping> findByControlId(Long controlId);

    // ── Tenant-visible variants ─────────────────────────────────────────────
    // Controls are shared across every tenant, so the mappings hanging off one
    // control belong to many companies. findByControlId above returns all of
    // them; callers were filtering in Java afterwards, which is correct but
    // means the database hands over other tenants' rows first.
    //
    // Two derived finders rather than one @Query: the project convention is no
    // @Query annotations (see DbRepository), and "tenant_id IS NULL OR
    // tenant_id = ?" has no sane derived-name spelling. Both are indexed, and
    // the pair costs one extra round trip on paths that were already looping.

    List<AuditPolicyControlMapping> findByControlIdAndTenantIdIsNull(Long controlId);

    List<AuditPolicyControlMapping> findByControlIdAndTenantId(Long controlId, Long tenantId);

    /** Platform-owned mappings plus this tenant's own — never another tenant's. */
    default List<AuditPolicyControlMapping> findVisibleByControlId(Long controlId, Long tenantId) {
        List<AuditPolicyControlMapping> out =
                new java.util.ArrayList<>(findByControlIdAndTenantIdIsNull(controlId));
        if (tenantId != null) out.addAll(findByControlIdAndTenantId(controlId, tenantId));
        return out;
    }

    Optional<AuditPolicyControlMapping> findByPolicyIdAndControlId(Long policyId, Long controlId);

    void deleteByPolicyId(Long policyId);

    void deleteByControlId(Long controlId);
}