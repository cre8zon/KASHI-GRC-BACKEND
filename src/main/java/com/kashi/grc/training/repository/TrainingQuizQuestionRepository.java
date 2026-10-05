package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TrainingQuizQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface TrainingQuizQuestionRepository extends JpaRepository<TrainingQuizQuestion, Long> {

    List<TrainingQuizQuestion> findByCourseIdAndIsActiveTrueOrderBySortOrderAsc(Long courseId);

    List<TrainingQuizQuestion> findByCourseIdInAndIsActiveTrue(Collection<Long> courseIds);

    long countByCourseIdAndIsActiveTrue(Long courseId);
}
