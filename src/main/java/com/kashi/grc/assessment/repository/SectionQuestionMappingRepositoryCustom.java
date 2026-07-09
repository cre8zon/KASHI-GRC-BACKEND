package com.kashi.grc.assessment.repository;

/** Criteria API fragment for SectionQuestionMappingRepository. */
public interface SectionQuestionMappingRepositoryCustom {

    /** Total questions across all sections mapped to a template. */
    long countQuestionsForTemplate(Long templateId);
}
