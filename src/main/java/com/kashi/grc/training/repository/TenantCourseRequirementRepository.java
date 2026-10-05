package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TenantCourseRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantCourseRequirementRepository extends JpaRepository<TenantCourseRequirement, Long> {

    List<TenantCourseRequirement> findByTenantIdAndIsActiveTrue(Long tenantId);

    Optional<TenantCourseRequirement> findByTenantIdAndCourseId(Long tenantId, Long courseId);

    /** Every active requirement across every tenant — the recurrence sweep's input. */
    List<TenantCourseRequirement> findByIsActiveTrue();
}