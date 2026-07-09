package com.kashi.grc.vendor.repository;

import com.kashi.grc.vendor.domain.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Simple derived queries only — list/filter/sort via DbRepository (Criteria API).
 */
public interface VendorRepository extends JpaRepository<Vendor, Long> {

    Optional<Vendor> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    boolean existsByNameAndTenantIdAndIsDeletedFalse(String name, Long tenantId);

    long countByTenantId(Long tenantId);

    /**
     * Bulk fetch vendors by ID — single IN query, avoids the former N+1 in
     * AssessmentController.listAssessments(). Derived query — no @Query needed.
     */
    List<Vendor> findAllByIdIn(Collection<Long> ids);
}
