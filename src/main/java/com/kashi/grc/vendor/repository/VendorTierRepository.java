package com.kashi.grc.vendor.repository;

import com.kashi.grc.vendor.domain.VendorTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VendorTierRepository extends JpaRepository<VendorTier, Long> {
    List<VendorTier> findByTenantId(Long tenantId);
    Optional<VendorTier> findByIdAndTenantId(Long id, Long tenantId);
}
