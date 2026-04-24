package com.kashi.grc.vendor.repository;

import com.kashi.grc.vendor.domain.VendorContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VendorContractRepository extends JpaRepository<VendorContract, Long> {
    Optional<VendorContract> findByIdAndTenantId(Long id, Long tenantId);
}
