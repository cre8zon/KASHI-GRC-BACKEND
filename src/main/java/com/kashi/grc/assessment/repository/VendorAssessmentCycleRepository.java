package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.VendorAssessmentCycle;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VendorAssessmentCycleRepository extends JpaRepository<VendorAssessmentCycle, Long> {
    List<VendorAssessmentCycle> findByVendorIdOrderByCycleNo(Long vendorId);
    long countByVendorId(Long vendorId);
    // Direct lookup by workflow instance — O(1) instead of load-all-then-filter
    java.util.Optional<VendorAssessmentCycle> findByWorkflowInstanceId(Long workflowInstanceId);
}