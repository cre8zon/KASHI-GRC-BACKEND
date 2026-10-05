package com.kashi.grc.questionnaire.repository;

import com.kashi.grc.questionnaire.domain.InboundQuestionnaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InboundQuestionnaireRepository extends JpaRepository<InboundQuestionnaire, Long> {

    boolean existsByQuestionnaireRefAndTenantId(String ref, Long tenantId);

    @Query("SELECT COALESCE(MAX(q.id), 0) + 1 FROM InboundQuestionnaire q WHERE q.tenantId = :tenantId")
    long nextRefSequence(@Param("tenantId") Long tenantId);

    List<InboundQuestionnaire> findByTenantIdAndIsDeletedFalse(Long tenantId);
}
