package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssessmentReportRepository extends JpaRepository<AssessmentReport, Long> {

    List<AssessmentReport> findByAssessmentIdOrderByReportVersionDesc(Long assessmentId);

    Optional<AssessmentReport> findTopByAssessmentIdOrderByReportVersionDesc(Long assessmentId);

    long countByAssessmentId(Long assessmentId);
}