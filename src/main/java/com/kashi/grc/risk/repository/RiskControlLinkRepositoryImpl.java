package com.kashi.grc.risk.repository;

import com.kashi.grc.audit.domain.AuditControlInstance;
import com.kashi.grc.risk.domain.RiskControlLink;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Criteria implementation of the risk -> control -> instance traversal.
 *
 * Written here rather than as a derived method on AuditControlInstanceRepository
 * so the risk module owns its own reads and the audit module needs no edit.
 */
public class RiskControlLinkRepositoryImpl implements RiskControlLinkRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Map<Long, String> findEffectivenessByRiskId(Long riskId, Long tenantId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<RiskControlLink> l = cq.from(RiskControlLink.class);
        cq.select(l.get("controlId")).where(cb.equal(l.get("riskId"), riskId));

        List<Long> controlIds = em.createQuery(cq).getResultList();
        return findEffectivenessByControlIds(controlIds, tenantId);
    }

    @Override
    public Map<Long, String> findEffectivenessByControlIds(List<Long> controlIds, Long tenantId) {
        if (controlIds == null || controlIds.isEmpty()) return Map.of();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AuditControlInstance> cq = cb.createQuery(AuditControlInstance.class);
        Root<AuditControlInstance> ci = cq.from(AuditControlInstance.class);

        cq.where(
                ci.get("originalControlId").in(controlIds),
                cb.equal(ci.get("tenantId"), tenantId)
        );

        List<AuditControlInstance> instances = em.createQuery(cq).getResultList();

        // Most recently tested instance per control wins. testedAt is null until
        // a result is recorded, so an untested instance never displaces a tested
        // one — which is the point of comparing on it rather than on id.
        Map<Long, AuditControlInstance> latest = new HashMap<>();
        for (AuditControlInstance inst : instances) {
            Long key = inst.getOriginalControlId();
            if (key == null) continue;
            AuditControlInstance held = latest.get(key);
            if (held == null || isNewer(inst, held)) latest.put(key, inst);
        }

        Map<Long, String> out = new LinkedHashMap<>();
        for (Map.Entry<Long, AuditControlInstance> e : latest.entrySet()) {
            AuditControlInstance.TestResult result = e.getValue().getTestResult();
            if (result != null) out.put(e.getKey(), result.name());
        }
        return out;
    }

    private boolean isNewer(AuditControlInstance candidate, AuditControlInstance held) {
        LocalDateTime a = candidate.getTestedAt();
        LocalDateTime b = held.getTestedAt();
        if (a == null && b == null) {
            return idOf(candidate) > idOf(held);        // deterministic tie-break
        }
        if (a == null) return false;
        if (b == null) return true;
        return a.isAfter(b);
    }

    private long idOf(AuditControlInstance i) {
        return i.getId() != null ? i.getId() : 0L;
    }
}
