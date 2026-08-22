package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditControlTestMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditControlTestMappingRepository extends JpaRepository<AuditControlTestMapping, Long> {

    List<AuditControlTestMapping> findByControlIdOrderByOrderNoAsc(Long controlId);

    // Same reasoning as AuditPolicyControlMappingRepository.findVisibleByControlId.
    // Test mappings are almost all global today, but the tenant column exists and
    // is stamped on write, so the filter belongs in the query rather than in the
    // caller.

    List<AuditControlTestMapping> findByControlIdAndTenantIdIsNullOrderByOrderNoAsc(Long controlId);

    List<AuditControlTestMapping> findByControlIdAndTenantIdOrderByOrderNoAsc(Long controlId, Long tenantId);

    /** Platform-owned mappings plus this tenant's own, order_no ascending. */
    default List<AuditControlTestMapping> findVisibleByControlId(Long controlId, Long tenantId) {
        List<AuditControlTestMapping> out =
                new java.util.ArrayList<>(findByControlIdAndTenantIdIsNullOrderByOrderNoAsc(controlId));
        if (tenantId != null) out.addAll(findByControlIdAndTenantIdOrderByOrderNoAsc(controlId, tenantId));
        out.sort(java.util.Comparator.comparing(
                AuditControlTestMapping::getOrderNo,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return out;
    }

    List<AuditControlTestMapping> findByTestIdOrderByOrderNoAsc(Long testId);

    Optional<AuditControlTestMapping> findByControlIdAndTestId(Long controlId, Long testId);

    void deleteByControlId(Long controlId);

    void deleteByTestId(Long testId);
}