package com.kashi.grc.issue.repository;

import com.kashi.grc.issue.domain.Issue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Criteria API implementation of IssueRepositoryCustom.
 *
 * Conversion notes:
 *  - JPQL enum literals (Issue$Status.RESOLVED ...) become type-safe
 *    Issue.Status constants passed to Path.in(...) wrapped in cb.not(...).
 *  - GROUP BY dashboards use CriteriaQuery<Object[]> + multiselect + groupBy —
 *    return shape (List<Object[]>) is identical to the former JPQL.
 *  - closeIssue uses CriteriaUpdate — the Criteria equivalent of
 *    @Modifying UPDATE.
 */
public class IssueRepositoryImpl implements IssueRepositoryCustom {

    /** Statuses considered terminal for SLA escalation purposes. */
    private static final List<Issue.Status> ESCALATION_TERMINAL = List.of(
            Issue.Status.RESOLVED,
            Issue.Status.ACCEPTED_RISK,
            Issue.Status.CLOSED,
            Issue.Status.DUPLICATE);

    /** Statuses excluded from "open" dashboard counts. */
    private static final List<Issue.Status> DASHBOARD_CLOSED = List.of(
            Issue.Status.CLOSED,
            Issue.Status.DUPLICATE);

    @PersistenceContext
    private EntityManager em;

    // ── 1. nextIssueRefSequence ───────────────────────────────────────────

    @Override
    public long nextIssueRefSequence(Long tenantId) {
        LocalDateTime startOfYear     = LocalDate.now().withDayOfYear(1).atStartOfDay();
        LocalDateTime startOfNextYear = startOfYear.plusYears(1);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Issue> i = cq.from(Issue.class);

        cq.select(cb.count(i)).where(
                cb.equal(i.get("tenantId"), tenantId),
                cb.greaterThanOrEqualTo(i.get("createdAt"), startOfYear),
                cb.lessThan(i.get("createdAt"), startOfNextYear)
        );
        Long count = em.createQuery(cq).getSingleResult();
        return (count != null ? count : 0L) + 1;
    }

    // ── 2. SLA escalation scheduler ───────────────────────────────────────

    @Override
    public List<Issue> findBreachedIssues(LocalDateTime now) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Issue> cq = cb.createQuery(Issue.class);
        Root<Issue> i = cq.from(Issue.class);

        cq.where(
                cb.isNotNull(i.get("tenantId")),
                cb.isNotNull(i.get("dueAt")),
                cb.lessThan(i.get("dueAt"), now),
                cb.isFalse(i.get("slaBreached")),
                cb.not(i.get("status").in(ESCALATION_TERMINAL))
        );
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Issue> findActiveBreachedForReescalation(LocalDateTime cutoff) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Issue> cq = cb.createQuery(Issue.class);
        Root<Issue> i = cq.from(Issue.class);

        cq.where(
                cb.isNotNull(i.get("tenantId")),
                cb.isTrue(i.get("slaBreached")),
                cb.or(
                        cb.isNull(i.get("lastEscalatedAt")),
                        cb.lessThan(i.get("lastEscalatedAt"), cutoff)
                ),
                cb.not(i.get("status").in(ESCALATION_TERMINAL))
        );
        return em.createQuery(cq).getResultList();
    }

    // ── 3. Dashboard / analytics ──────────────────────────────────────────

    @Override
    public List<Object[]> countByStatusForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Issue> i = cq.from(Issue.class);

        cq.multiselect(i.get("status"), cb.count(i))
                .where(cb.equal(i.get("tenantId"), tenantId))
                .groupBy(i.get("status"));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public List<Object[]> countOpenBySeverityForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Issue> i = cq.from(Issue.class);

        cq.multiselect(i.get("severity"), cb.count(i))
                .where(
                        cb.equal(i.get("tenantId"), tenantId),
                        cb.not(i.get("status").in(DASHBOARD_CLOSED))
                )
                .groupBy(i.get("severity"));
        return em.createQuery(cq).getResultList();
    }

    @Override
    public long countSlaBreachedForTenant(Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Issue> i = cq.from(Issue.class);

        cq.select(cb.count(i)).where(
                cb.equal(i.get("tenantId"), tenantId),
                cb.isTrue(i.get("slaBreached")),
                cb.not(i.get("status").in(DASHBOARD_CLOSED))
        );
        Long result = em.createQuery(cq).getSingleResult();
        return result != null ? result : 0L;
    }

    // ── 4. Bulk status update ─────────────────────────────────────────────

    @Override
    public int closeIssue(Long id, Long tenantId, Issue.Status status,
                          LocalDateTime now, Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaUpdate<Issue> cu = cb.createCriteriaUpdate(Issue.class);
        Root<Issue> i = cu.from(Issue.class);

        cu.set(i.get("status"),   status)
          .set(i.get("closedAt"), now)
          .set(i.get("closedBy"), userId)
          .where(
                  cb.equal(i.get("id"), id),
                  cb.equal(i.get("tenantId"), tenantId)
          );
        return em.createQuery(cu).executeUpdate();
    }
}
