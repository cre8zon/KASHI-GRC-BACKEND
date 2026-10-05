package com.kashi.grc.risk.repository;

import java.util.List;
import java.util.Map;

public interface RiskControlLinkRepositoryCustom {

    /**
     * Latest test_result per linked LIBRARY control, for one risk.
     *
     * Traverses risk_control_links.control_id
     *        -> audit_control_instances.original_control_id
     *        -> test_result
     * and keeps the most recently tested instance per control, so a control
     * tested across three engagements reports its current state rather than
     * whichever row the database happened to return first.
     *
     * Returns controlId -> test_result name. Controls with no instance at all
     * are absent from the map; the caller renders those as NOT_TESTED.
     *
     * Scoped to the tenant, because a control is global but its instances are
     * not — reading them unscoped would leak another organisation's audit
     * results through a shared library control.
     */
    Map<Long, String> findEffectivenessByRiskId(Long riskId, Long tenantId);

    /** Same traversal for many controls at once. */
    Map<Long, String> findEffectivenessByControlIds(List<Long> controlIds, Long tenantId);
}
