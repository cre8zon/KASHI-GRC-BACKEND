package com.kashi.grc.questionnaire.repository;

import com.kashi.grc.questionnaire.domain.InboundQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InboundQuestionRepository extends JpaRepository<InboundQuestion, Long> {

    List<InboundQuestion> findByQuestionnaireIdOrderBySortOrderAsc(Long questionnaireId);

    List<InboundQuestion> findByTenantId(Long tenantId);

    long countByQuestionnaireId(Long questionnaireId);
}
