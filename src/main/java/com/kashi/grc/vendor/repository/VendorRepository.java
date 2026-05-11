package com.kashi.grc.vendor.repository;

import com.kashi.grc.vendor.domain.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Simple derived queries only — list/filter/sort via CriteriaQueryHelper.
 */
public interface VendorRepository extends JpaRepository<Vendor, Long> {

    Optional<Vendor> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    boolean existsByNameAndTenantIdAndIsDeletedFalse(String name, Long tenantId);

    long countByTenantId(Long tenantId);

    /**
     * Bulk fetch vendors by ID — single IN query.
     *
     * Replaces the N+1 pattern in AssessmentController.listAssessments() where
     * vendorRepository.findById(a.getVendorId()) was called inside the page mapper
     * lambda, firing one SELECT per assessment row in the page.
     *
     * Usage:
     *   Set<Long> vendorIds = page.stream().map(VendorAssessment::getVendorId).collect(toSet());
     *   Map<Long, Vendor> vendorMap = vendorRepository.findAllByIdIn(vendorIds)
     *           .stream().collect(toMap(Vendor::getId, v -> v));
     *   // Then use vendorMap.get(a.getVendorId()) instead of findById in the mapper.
     */
    @Query("SELECT v FROM Vendor v WHERE v.id IN :ids")
    List<Vendor> findAllByIdIn(@Param("ids") Collection<Long> ids);
}
