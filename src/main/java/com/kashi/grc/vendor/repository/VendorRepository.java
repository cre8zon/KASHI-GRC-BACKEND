package com.kashi.grc.vendor.repository;

import com.kashi.grc.vendor.domain.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Simple derived queries only — list/filter/sort via CriteriaQueryHelper.
 */
public interface VendorRepository extends JpaRepository<Vendor, Long> {

    Optional<Vendor> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    boolean existsByNameAndTenantIdAndIsDeletedFalse(String name, Long tenantId);

    long countByTenantId(Long tenantId);
}
