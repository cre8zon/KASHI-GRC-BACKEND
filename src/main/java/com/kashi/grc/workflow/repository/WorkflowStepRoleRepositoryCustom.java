package com.kashi.grc.workflow.repository;

/** Criteria API fragment for WorkflowStepRoleRepository. */
public interface WorkflowStepRoleRepositoryCustom {

    /**
     * Bulk delete by stepId (CriteriaDelete — single DELETE statement, same as
     * the former @Modifying JPQL). Caller must be @Transactional.
     */
    void deleteByStepId(Long stepId);
}
