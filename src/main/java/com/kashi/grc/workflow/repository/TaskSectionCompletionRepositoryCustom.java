package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.TaskSectionCompletion;
import java.util.List;

/** Criteria API fragment for TaskSectionCompletionRepository. */
public interface TaskSectionCompletionRepositoryCustom {

    /** Count required sections marked completed for a task. */
    long countCompletedRequired(Long taskInstanceId);

    /** Count all required sections for a task. */
    long countTotalRequired(Long taskInstanceId);

    /** Required sections not yet completed — gate before COMPLETE_STEP. */
    List<TaskSectionCompletion> findIncompleteRequired(Long taskInstanceId);
}
