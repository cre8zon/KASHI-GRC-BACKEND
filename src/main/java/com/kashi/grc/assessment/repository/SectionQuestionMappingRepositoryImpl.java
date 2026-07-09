package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.SectionQuestionMapping;
import com.kashi.grc.assessment.domain.TemplateSectionMapping;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

/** JPA Criteria API implementation of SectionQuestionMappingRepositoryCustom. */
public class SectionQuestionMappingRepositoryImpl implements SectionQuestionMappingRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long countQuestionsForTemplate(Long templateId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<SectionQuestionMapping> sqm = cq.from(SectionQuestionMapping.class);

        Subquery<Long> templateSections = cq.subquery(Long.class);
        Root<TemplateSectionMapping> tsm = templateSections.from(TemplateSectionMapping.class);
        templateSections.select(tsm.get("sectionId"))
                .where(cb.equal(tsm.get("templateId"), templateId));

        cq.select(cb.count(sqm)).where(sqm.get("sectionId").in(templateSections));
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }
}
