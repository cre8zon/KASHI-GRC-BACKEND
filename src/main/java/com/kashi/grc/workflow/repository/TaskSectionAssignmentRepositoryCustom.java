package com.kashi.grc.workflow.repository;

/** Criteria API fragment for TaskSectionAssignmentRepository. */
public interface TaskSectionAssignmentRepositoryCustom {

    /** Count non-COMPLETED assignments for a task + section key. */
    long countIncomplete(Long taskInstanceId, String sectionKey);
}
