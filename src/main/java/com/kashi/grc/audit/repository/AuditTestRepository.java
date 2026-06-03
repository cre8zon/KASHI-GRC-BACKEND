// ─── AuditTestRepository.java ─────────────────────────────────────────────────
package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditTestRepository extends JpaRepository<AuditTest, Long> {

    /**
     * Count existing tests for this tenant (used to generate next AT-NNN ref).
     * Includes global tests (tenantId IS NULL) + tenant-scoped tests.
     */
    @Query("SELECT COUNT(t) FROM AuditTest t WHERE t.tenantId = :tenantId OR t.tenantId IS NULL")
    long countForTenant(@Param("tenantId") Long tenantId);

    /** Check if a testRef is already taken within this tenant scope */
    boolean existsByTestRefAndTenantId(String testRef, Long tenantId);


    List<AuditTest> findByTenantIdIsNullOrTenantId(Long tenantId);

    List<AuditTest> findByControlTag(String controlTag);

    Optional<AuditTest> findByNameAndTenantId(String name, Long tenantId);

    @Query("SELECT t FROM AuditTest t WHERE " +
            "(t.tenantId IS NULL OR t.tenantId = :tenantId) AND " +
            "LOWER(t.name) LIKE LOWER(CONCAT('%', :search, '%'))")
    List<AuditTest> searchByName(@Param("tenantId") Long tenantId,
                                 @Param("search") String search);
}