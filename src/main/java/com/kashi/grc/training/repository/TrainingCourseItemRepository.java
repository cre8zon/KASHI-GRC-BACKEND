package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TrainingCourseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface TrainingCourseItemRepository extends JpaRepository<TrainingCourseItem, Long> {

    List<TrainingCourseItem> findByCourseIdAndIsDeletedFalseOrderBySortOrderAsc(Long courseId);

    List<TrainingCourseItem> findByCourseIdInAndIsDeletedFalse(Collection<Long> courseIds);

    long countByCourseIdAndIsDeletedFalse(Long courseId);
}
