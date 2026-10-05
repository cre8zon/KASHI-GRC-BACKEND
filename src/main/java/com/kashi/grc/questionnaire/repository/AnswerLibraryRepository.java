package com.kashi.grc.questionnaire.repository;

import com.kashi.grc.questionnaire.domain.AnswerLibraryEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnswerLibraryRepository extends JpaRepository<AnswerLibraryEntry, Long> {

    List<AnswerLibraryEntry> findByTenantIdAndIsDeletedFalse(Long tenantId);

    List<AnswerLibraryEntry> findByTenantIdAndStatusAndIsDeletedFalse(
            Long tenantId, AnswerLibraryEntry.Status status);
}
