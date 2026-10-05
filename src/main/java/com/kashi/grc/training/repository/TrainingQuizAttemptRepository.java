package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TrainingQuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrainingQuizAttemptRepository extends JpaRepository<TrainingQuizAttempt, Long> {
    List<TrainingQuizAttempt> findByAssignmentIdOrderByAttemptNumberAsc(Long assignmentId);
    long countByAssignmentId(Long assignmentId);
}
