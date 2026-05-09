package com.kashi.grc.vendor.repository;

import com.kashi.grc.vendor.domain.VendorTemplateSelection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VendorTemplateSelectionRepository extends JpaRepository<VendorTemplateSelection, Long> {

    Optional<VendorTemplateSelection> findByWorkflowInstanceId(Long workflowInstanceId);
}