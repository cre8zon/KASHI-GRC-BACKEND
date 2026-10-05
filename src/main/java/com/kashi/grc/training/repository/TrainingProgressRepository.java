package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TrainingProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrainingProgressRepository extends JpaRepository<TrainingProgress, Long> {
    List<TrainingProgress> findByAssignmentId(Long assignmentId);
    Optional<TrainingProgress> findByAssignmentIdAndItemId(Long assignmentId, Long itemId);
}
