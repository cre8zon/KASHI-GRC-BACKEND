package com.kashi.grc.training.repository;

import com.kashi.grc.training.domain.TrainingCourse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrainingCourseRepository extends JpaRepository<TrainingCourse, Long>,
        JpaSpecificationExecutor<TrainingCourse>, TrainingCourseRepositoryCustom {

    /** A tenant's own course. Returns empty for a platform library row — deliberately. */
    Optional<TrainingCourse> findByIdAndTenantId(Long id, Long tenantId);

    List<TrainingCourse> findByTenantIdIsNullAndIsDeletedFalse();

    boolean existsByCourseRefAndTenantId(String courseRef, Long tenantId);
}
