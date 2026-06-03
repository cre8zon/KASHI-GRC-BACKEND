package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditPolicyRepository extends JpaRepository<AuditPolicy, Long> {

    @Query("SELECT COUNT(p) FROM AuditPolicy p WHERE p.tenantId = :tenantId OR p.tenantId IS NULL")
    long countForTenant(@Param("tenantId") Long tenantId);

    boolean existsByPolicyRefAndTenantId(String policyRef, Long tenantId);


    // FIX: include global policies (tenantId IS NULL) alongside tenant-scoped ones
    @Query("SELECT p FROM AuditPolicy p WHERE (p.tenantId = :tenantId OR p.tenantId IS NULL) ORDER BY p.title ASC")
    List<AuditPolicy> findByTenantIdOrderByTitleAsc(@Param("tenantId") Long tenantId);

    @Query("SELECT p FROM AuditPolicy p WHERE (p.tenantId = :tenantId OR p.tenantId IS NULL) AND p.status = :status")
    List<AuditPolicy> findByTenantIdAndStatus(@Param("tenantId") Long tenantId,
                                              @Param("status") AuditPolicy.PolicyStatus status);

    // For lookup by ref: check tenant-scoped first, then fall back to global
    @Query("SELECT p FROM AuditPolicy p WHERE p.policyRef = :policyRef AND (p.tenantId = :tenantId OR p.tenantId IS NULL) ORDER BY p.tenantId DESC")
    List<AuditPolicy> findByPolicyRefForTenant(@Param("policyRef") String policyRef,
                                               @Param("tenantId") Long tenantId);

    // Keep original for backward compat (used by CSV import upsert with exact tenant match)
    Optional<AuditPolicy> findByPolicyRefAndTenantId(String policyRef, Long tenantId);

    @Query("SELECT p FROM AuditPolicy p WHERE (p.tenantId = :tenantId OR p.tenantId IS NULL) " +
            "AND LOWER(p.title) LIKE LOWER(CONCAT('%', :search, '%'))")
    List<AuditPolicy> searchByTitle(@Param("tenantId") Long tenantId,
                                    @Param("search") String search);

    /** Policies with a review due within N days — for notification scheduling */
    @Query("SELECT p FROM AuditPolicy p WHERE (p.tenantId = :tenantId OR p.tenantId IS NULL) " +
            "AND p.nextReviewDate <= :reviewBefore AND p.status = 'APPROVED'")
    List<AuditPolicy> findDueForReview(@Param("tenantId") Long tenantId,
                                       @Param("reviewBefore") java.time.LocalDate reviewBefore);
}