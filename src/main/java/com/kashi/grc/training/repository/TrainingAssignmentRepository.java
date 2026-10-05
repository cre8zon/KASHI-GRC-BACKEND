package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TrainingAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrainingAssignmentRepository extends JpaRepository<TrainingAssignment, Long>,
        JpaSpecificationExecutor<TrainingAssignment>, TrainingAssignmentRepositoryCustom {

    Optional<TrainingAssignment> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    List<TrainingAssignment> findByTenantIdAndPersonnelIdAndIsDeletedFalse(Long tenantId, Long personnelId);

    /** Idempotency for assignment: one row per person per target per version. */
    Optional<TrainingAssignment> findByPersonnelIdAndTargetTypeAndTargetIdAndTargetVersion(
            Long personnelId, TrainingAssignment.TargetType targetType, Long targetId, Integer targetVersion);

    List<TrainingAssignment> findByTenantIdAndTargetTypeAndTargetIdAndIsDeletedFalse(
            Long tenantId, TrainingAssignment.TargetType targetType, Long targetId);

    List<TrainingAssignment> findByPersonnelIdInAndTenantIdAndIsDeletedFalse(
            Collection<Long> personnelIds, Long tenantId);

    long countByTenantIdAndTargetTypeAndTargetIdAndIsDeletedFalse(
            Long tenantId, TrainingAssignment.TargetType targetType, Long targetId);
}
