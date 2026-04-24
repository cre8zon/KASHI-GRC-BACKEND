package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.VendorAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VendorAssessmentRepository extends JpaRepository<VendorAssessment, Long> {
    Optional<VendorAssessment> findByIdAndTenantId(Long id, Long tenantId);
    List<VendorAssessment> findByVendorId(Long vendorId);
    List<VendorAssessment> findByCycleId(Long cycleId);
}
