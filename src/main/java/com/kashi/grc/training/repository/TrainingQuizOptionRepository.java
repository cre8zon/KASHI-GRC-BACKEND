package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TrainingQuizOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface TrainingQuizOptionRepository extends JpaRepository<TrainingQuizOption, Long> {

    List<TrainingQuizOption> findByQuestionIdOrderBySortOrderAsc(Long questionId);

    /**
     * Batch: one query for every option across a whole quiz.
     *
     * Grading loads options through THIS method, fresh from the database, and
     * never trusts an isCorrect flag that came back from a browser.
     */
    List<TrainingQuizOption> findByQuestionIdIn(Collection<Long> questionIds);
}
