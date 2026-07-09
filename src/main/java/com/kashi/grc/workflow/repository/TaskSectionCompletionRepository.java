package com.kashi.grc.workflow.repository;

import com.kashi.grc.workflow.domain.TaskSectionCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Required-section counts/lists live in the Custom fragment (Criteria API). */
@Repository
public interface TaskSectionCompletionRepository
        extends JpaRepository<TaskSectionCompletion, Long>, TaskSectionCompletionRepositoryCustom {

    List<TaskSectionCompletion> findByTaskInstanceIdOrderBySnapSectionOrderAsc(Long taskInstanceId);

    Optional<TaskSectionCompletion> findByTaskInstanceIdAndSnapSectionKey(
            Long taskInstanceId, String snapSectionKey);

    Optional<TaskSectionCompletion> findByTaskInstanceIdAndSnapCompletionEvent(
            Long taskInstanceId, String snapCompletionEvent);

    boolean existsByTaskInstanceId(Long taskInstanceId);

    List<TaskSectionCompletion> findByStepInstanceId(Long stepInstanceId);
}
