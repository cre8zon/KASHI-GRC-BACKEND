// ─── AuditTestRepository.java ─────────────────────────────────────────────────
package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/** countForTenant / searchByName live in the Custom fragment (Criteria API). */
@Repository
public interface AuditTestRepository
        extends JpaRepository<AuditTest, Long>, AuditTestRepositoryCustom {

    /** Check if a testRef is already taken within this tenant scope */
    boolean existsByTestRefAndTenantId(String testRef, Long tenantId);

    List<AuditTest> findByTenantIdIsNullOrTenantId(Long tenantId);

    List<AuditTest> findByControlTag(String controlTag);

    Optional<AuditTest> findByNameAndTenantId(String name, Long tenantId);

    /** Indexed lookup for CSV library import — was a full findAll()+filter per row,
     *  reused by upsertTest and upsertControlTestMapping. */
    Optional<AuditTest> findByTestRefAndTenantId(String testRef, Long tenantId);
}