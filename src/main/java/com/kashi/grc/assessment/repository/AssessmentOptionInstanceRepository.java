package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentOptionInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssessmentOptionInstanceRepository
        extends JpaRepository<AssessmentOptionInstance, Long> {

    List<AssessmentOptionInstance> findByQuestionInstanceIdOrderByOrderNo(Long questionInstanceId);
}