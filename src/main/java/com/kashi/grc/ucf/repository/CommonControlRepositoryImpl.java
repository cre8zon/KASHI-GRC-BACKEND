package com.kashi.grc.ucf.repository;

import com.kashi.grc.ucf.domain.CommonControl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CommonControlRepositoryImpl implements CommonControlRepositoryCustom {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<CommonControl> searchSelectable(String query, String domainCode, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<CommonControl> cq = cb.createQuery(CommonControl.class);
        Root<CommonControl> c = cq.from(CommonControl.class);

        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(c.get("nodeLevel"), CommonControl.NodeLevel.CONTROL));
        where.add(cb.isTrue(c.get("active")));

        if (domainCode != null && !domainCode.isBlank()) {
            where.add(cb.equal(c.get("domainCode"), domainCode.toUpperCase().trim()));
        }

        if (query != null && !query.isBlank()) {
            String like = "%" + query.toLowerCase().trim() + "%";
            where.add(cb.or(
                    cb.like(cb.lower(c.get("code")), like),
                    cb.like(cb.lower(c.get("title")), like),
                    cb.like(cb.lower(cb.coalesce(c.get("legacyTag"), "")), like)
            ));
        }

        cq.where(where.toArray(new Predicate[0]));
        cq.orderBy(cb.asc(c.get("domainCode")), cb.asc(c.get("sortOrder")));

        return em.createQuery(cq)
                .setMaxResults(limit > 0 ? limit : 50)
                .getResultList();
    }

    /**
     * Loads the catalogue once and walks parent_code in memory.
     *
     * It is a few hundred effectively-static rows, so one scan beats a query
     * per level. If it ever passes a few thousand entries this becomes a
     * recursive CTE — but not before.
     */
    @Override
    public List<CommonControl> findAncestryChain(String code) {
        if (code == null || code.isBlank()) return List.of();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<CommonControl> cq = cb.createQuery(CommonControl.class);
        Root<CommonControl> c = cq.from(CommonControl.class);
        cq.select(c);

        Map<String, CommonControl> byCode = new LinkedHashMap<>();
        for (CommonControl cc : em.createQuery(cq).getResultList()) {
            byCode.put(cc.getCode(), cc);
        }

        List<CommonControl> chain = new ArrayList<>();
        String cursor = code;
        int guard = 0;
        while (cursor != null && guard++ < 10) {          // cycle guard
            CommonControl node = byCode.get(cursor);
            if (node == null) break;
            chain.add(node);
            cursor = node.getParentCode();
        }
        return chain;
    }
}