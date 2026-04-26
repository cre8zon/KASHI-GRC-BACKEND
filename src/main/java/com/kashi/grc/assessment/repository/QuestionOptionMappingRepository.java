// ── QuestionOptionMappingRepository.java ─────────────────────────────────────
package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.QuestionOptionMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface QuestionOptionMappingRepository extends JpaRepository<QuestionOptionMapping, Long> {

    List<QuestionOptionMapping> findByQuestionIdOrderByOrderNo(Long questionId);

    // Bulk variant — loads all option mappings for multiple questions in one query.
    // Used by ExecuteAssessmentAction to replace N individual findByQuestionId calls.
    List<QuestionOptionMapping> findByQuestionIdInOrderByOrderNo(java.util.Collection<Long> questionIds);

    List<QuestionOptionMapping> findByOptionId(Long optionId);

    Optional<QuestionOptionMapping> findByQuestionIdAndOptionId(Long questionId, Long optionId);

    boolean existsByQuestionIdAndOptionId(Long questionId, Long optionId);

    void deleteByQuestionId(Long questionId);

    void deleteByOptionId(Long optionId);

    void deleteByQuestionIdAndOptionId(Long questionId, Long optionId);
}