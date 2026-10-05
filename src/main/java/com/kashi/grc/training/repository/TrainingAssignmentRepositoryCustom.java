package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TrainingAssignment;

import java.util.List;

public interface TrainingAssignmentRepositoryCustom {

    List<Object[]> countByStatusForTenant(Long tenantId);

    /**
     * Assigned or in progress, past the deadline.
     *
     * The headline number for the module and the one an auditor asks for:
     * mandatory training that is late, right now. Derived from due_at rather
     * than from a stored OVERDUE status, so it cannot drift.
     */
    List<TrainingAssignment> findOverdue(Long tenantId);

    /** The highest recurrence cycle already assigned for a course to a person. */
    Integer maxVersionFor(Long personnelId, TrainingAssignment.TargetType targetType, Long targetId);
}
