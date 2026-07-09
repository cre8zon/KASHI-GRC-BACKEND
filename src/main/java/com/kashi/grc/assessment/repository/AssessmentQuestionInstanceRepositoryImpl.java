package com.kashi.grc.assessment.repository;

import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** JPA Criteria API implementation of AssessmentQuestionInstanceRepositoryCustom. */
public class AssessmentQuestionInstanceRepositoryImpl
        implements AssessmentQuestionInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Double sumWeightByAssessmentId(Long assessmentId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Double> cq = cb.createQuery(Double.class);
        Root<AssessmentQuestionInstance> q = cq.from(AssessmentQuestionInstance.class);

        // CASE WHEN weight IS NOT NULL THEN weight ELSE 1.0 END
        Expression<Double> weightOrOne = cb.<Double>selectCase()
                .when(cb.isNotNull(q.get("weight")), q.<Double>get("weight"))
                .otherwise(cb.literal(1.0));

        cq.select(cb.coalesce(cb.sum(weightOrOne), 0.0))
                .where(cb.equal(q.get("assessmentId"), assessmentId));
        return em.createQuery(cq).getSingleResult();
    }

    @Override
    public List<Map<String, Object>> findByTenantIdAndQuestionTagSnapshot(Long tenantId, String tag) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<AssessmentQuestionInstance> q = cq.from(AssessmentQuestionInstance.class);

        cq.multiselect(q.get("id"), q.get("assignedUserId"))
                .where(
                        cb.equal(q.get("tenantId"), tenantId),
                        cb.equal(q.get("questionTagSnapshot"), tag)
                );

        return em.createQuery(cq).getResultList().stream()
                .map(row -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", row[0]);
                    m.put("assignedUserId", row[1]);   // may be null — HashMap tolerates it
                    return m;
                })
                .toList();
    }
}
