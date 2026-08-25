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
        return resolve(findByControlIdAndTenantIdIsNull(controlId),
                tenantId == null ? java.util.List.of()
                        : findByControlIdAndTenantId(controlId, tenantId));
    }

    /**
     * Everything the tenant may see, INCLUDING their exclusion rows, unresolved.
     *
     * Only the control screen wants this — it renders the excluded platform
     * policies in a collapsed "excluded" section so the tenant can restore them.
     * Every other caller wants findVisibleByControlId, which resolves them away.
     */
    default List<AuditPolicyControlMapping> findAllVisibleIncludingExclusions(Long controlId, Long tenantId) {
        List<AuditPolicyControlMapping> out =
                new java.util.ArrayList<>(findByControlIdAndTenantIdIsNull(controlId));
        if (tenantId != null) out.addAll(findByControlIdAndTenantId(controlId, tenantId));
        return out;
    }

    /**
     * ── EXCLUSION RESOLUTION ────────────────────────────────────────────────
     *
     * A tenant row with mappingType EXCLUDED pointing at a platform policy means
     * "not applicable to us". Resolving means: drop the platform row it targets,
     * and drop the exclusion row itself — it is an instruction, not a mapping.
     *
     * Deliberately here rather than in the callers. snapshotPolicies, the control
     * screen and any future reader all go through findVisibleByControlId, so the
     * rule cannot drift between what a tenant SEES and what an engagement GETS —
     * which is the failure that would matter most.
     *
     * An exclusion only ever suppresses a GLOBAL row. A tenant excluding their own
     * policy would just unlink it, and letting an exclusion cancel another tenant
     * row would make the same policy_id ambiguous.
     */
    private static List<AuditPolicyControlMapping> resolve(
            List<AuditPolicyControlMapping> globalRows,
            List<AuditPolicyControlMapping> tenantRows) {

        java.util.Set<Long> excludedPolicyIds = tenantRows.stream()
                .filter(m -> m.getMappingType() == AuditPolicyControlMapping.MappingType.EXCLUDED)
                .map(AuditPolicyControlMapping::getPolicyId)
                .collect(java.util.stream.Collectors.toSet());

        List<AuditPolicyControlMapping> out = new java.util.ArrayList<>();
        globalRows.stream()
                .filter(m -> !excludedPolicyIds.contains(m.getPolicyId()))
                .forEach(out::add);
        tenantRows.stream()
                .filter(m -> m.getMappingType() != AuditPolicyControlMapping.MappingType.EXCLUDED)
                .forEach(out::add);
        return out;
    }

    Optional<AuditPolicyControlMapping> findByPolicyIdAndControlId(Long policyId, Long controlId);

    void deleteByPolicyId(Long policyId);

    void deleteByControlId(Long controlId);
}