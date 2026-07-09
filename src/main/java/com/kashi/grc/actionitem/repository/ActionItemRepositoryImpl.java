package com.kashi.grc.actionitem.repository;

import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.assessment.domain.AssessmentQuestionInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.util.List;

/**
 * JPA Criteria API implementation of ActionItemRepositoryCustom.
 *
 * Conversion notes:
 *  - Status string literals → ActionItem.Status enum constants.
 *  - "CAST(a.entityType AS string) = :param" → entityType.as(String.class);
 *    the column is @Enumerated(STRING) varchar, so the cast is a no-op in SQL.
 *  - The ad-hoc "JOIN AssessmentQuestionInstance qi ON qi.id = a.entityId"
 *    becomes an IN-subquery on question-instance IDs — same result set.
 */
public class ActionItemRepositoryImpl implements ActionItemRepositoryCustom {

    private static final List<ActionItem.Status> OPEN_STATUSES = List.of(
            ActionItem.Status.OPEN,
            ActionItem.Status.IN_PROGRESS);

    private static final List<ActionItem.Status> LIVE_STATUSES = List.of(
            ActionItem.Status.OPEN,
            ActionItem.Status.IN_PROGRESS,
            ActionItem.Status.PENDING_REVIEW,
            ActionItem.Status.PENDING_VALIDATION);

    @PersistenceContext
    private EntityManager em;

    private long count(CriteriaQuery<Long> cq) {
        Long r = em.createQuery(cq).getSingleResult();
        return r != null ? r : 0L;
    }

    @Override
    public long countOpenForUser(Long userId, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<ActionItem> a = cq.from(ActionItem.class);
        cq.select(cb.count(a)).where(
                cb.equal(a.get("assignedTo"), userId),
                a.get("status").in(OPEN_STATUSES),
                cb.equal(a.get("tenantId"), tenantId)
        );
        return count(cq);
    }

    @Override
    public boolean existsOpenForSource(ActionItem.SourceType sourceType, Long sourceId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<ActionItem> a = cq.from(ActionItem.class);
        cq.select(cb.count(a)).where(
                cb.equal(a.get("sourceType"), sourceType),
                cb.equal(a.get("sourceId"), sourceId),
                a.get("status").in(OPEN_STATUSES)
        );
        return count(cq) > 0;
    }

    @Override
    public boolean existsOpenForEntity(String entityTypeStr, Long entityId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<ActionItem> a = cq.from(ActionItem.class);
        cq.select(cb.count(a)).where(
                cb.equal(a.get("entityType").as(String.class), entityTypeStr),
                cb.equal(a.get("entityId"), entityId),
                a.get("status").in(LIVE_STATUSES)
        );
        return count(cq) > 0;
    }

    @Override
    public boolean existsByAssignedToAndAssessmentId(Long userId, Long assessmentId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<ActionItem> a = cq.from(ActionItem.class);

        Subquery<Long> questionIds = cq.subquery(Long.class);
        Root<AssessmentQuestionInstance> qi = questionIds.from(AssessmentQuestionInstance.class);
        questionIds.select(qi.get("id"))
                .where(cb.equal(qi.get("assessmentId"), assessmentId));

        cq.select(cb.count(a)).where(
                cb.equal(a.get("assignedTo"), userId),
                a.get("entityId").in(questionIds),
                cb.equal(a.get("entityType").as(String.class), "QUESTION_RESPONSE")
        );
        return count(cq) > 0;
    }
}
