package com.kashi.grc.risk.repository;

import com.kashi.grc.risk.domain.Risk;

import java.time.LocalDate;
import java.util.List;

/**
 * Criteria API fragment for RiskRepository (Impl-suffix convention), matching
 * IssueRepositoryCustom / AssessmentResponseRepositoryCustom.
 */
public interface RiskRepositoryCustom {

    /**
     * Next per-tenant, per-calendar-year sequence for risk refs.
     * Sargable [Jan 1, Jan 1 next year) range rather than YEAR(created_at),
     * for the same reason nextIssueRefSequence uses one.
     */
    long nextRiskRefSequence(Long tenantId);

    /** Dashboard: [status, count] rows for a tenant's own risks. */
    List<Object[]> countByStatusForTenant(Long tenantId);

    /** Dashboard: [category, count] rows for a tenant's own risks. */
    List<Object[]> countByCategoryForTenant(Long tenantId);

    /**
     * Risks whose next_review_date has passed and which are not CLOSED.
     * Drives the review-overdue count on the stats endpoint. No scheduler
     * consumes it yet — deliberately, so nothing starts emailing before the
     * cadence has been agreed.
     */
    List<Risk> findOverdueForReview(Long tenantId, LocalDate asOf);
}
