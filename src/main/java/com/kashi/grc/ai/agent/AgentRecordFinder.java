package com.kashi.grc.ai.agent;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;
import jakarta.persistence.metamodel.EntityType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Reads the registers in AgentSource for one tenant, through the Criteria API.
 *
 * Three reads, all tenant-scoped and all read-only:
 *   stats  — record counts by status (and severity), so "how many open high
 *            issues" is answered from a count, not from a sample of ten;
 *   search — records whose ref, title or description contain the question's
 *            words, newest first, capped;
 *   byId   — one record, for the side panel on a record page.
 *
 * Generic over the entity class on purpose: one code path, one tenant filter,
 * one place to audit. Every query carries tenantId = :tenant; soft-deleted rows
 * are excluded where the entity has the flag.
 */
@Slf4j
@Component
public class AgentRecordFinder {

    @PersistenceContext
    private EntityManager em;

    /** A record flattened for the prompt and for its citation. */
    public record Found(Object id, String ref, String title, String description,
                        String status, String severity, Map<String, Object> fields) {}

    @Transactional(readOnly = true)
    public Map<String, Long> countByStatus(AgentSource s, Long tenantId) {
        return countBy(s, tenantId, s.statusAttr());
    }

    @Transactional(readOnly = true)
    public Map<String, Long> countBySeverity(AgentSource s, Long tenantId) {
        return countBy(s, tenantId, s.severityAttr());
    }

    private Map<String, Long> countBy(AgentSource s, Long tenantId, String attr) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (attr == null || !has(s, attr)) return out;
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<?> r = q.from(s.entity());
        Path<Object> p = r.get(attr);
        q.multiselect(p, cb.count(r)).where(scope(cb, r, s, tenantId)).groupBy(p);
        for (Tuple t : em.createQuery(q).getResultList()) {
            out.put(str(t.get(0)), (Long) t.get(1));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Found> search(AgentSource s, Long tenantId, List<String> words, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object> q = cb.createQuery(Object.class);
        Root<?> r = q.from(s.entity());

        List<Predicate> where = new ArrayList<>(List.of(scope(cb, r, s, tenantId)));
        if (words != null && !words.isEmpty()) {
            List<Predicate> any = new ArrayList<>();
            for (String w : words) {
                String like = "%" + w.toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
                for (String a : textAttrs(s)) any.add(cb.like(cb.lower(r.get(a).as(String.class)), like));
            }
            if (!any.isEmpty()) where.add(cb.or(any.toArray(new Predicate[0])));
        }
        q.select(r).where(where.toArray(new Predicate[0])).orderBy(cb.desc(r.get("id")));
        List<Object> rows = em.createQuery(q).setMaxResults(Math.max(1, limit)).getResultList();
        return rows.stream().map(o -> flatten(s, o)).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Found> byId(AgentSource s, Long tenantId, Long id) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object> q = cb.createQuery(Object.class);
        Root<?> r = q.from(s.entity());
        q.select(r).where(scope(cb, r, s, tenantId), cb.equal(r.get("id"), id));
        return em.createQuery(q).setMaxResults(1).getResultList().stream().findFirst().map(o -> flatten(s, o));
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** tenant_id = :tenant, and not soft-deleted where the entity can be. */
    private Predicate scope(CriteriaBuilder cb, Root<?> r, AgentSource s, Long tenantId) {
        List<Predicate> p = new ArrayList<>();
        p.add(cb.equal(r.get("tenantId"), tenantId));
        if (has(s, "isDeleted")) p.add(cb.isFalse(r.<Boolean>get("isDeleted")));
        return cb.and(p.toArray(new Predicate[0]));
    }

    private List<String> textAttrs(AgentSource s) {
        List<String> a = new ArrayList<>();
        if (s.refAttr() != null && has(s, s.refAttr())) a.add(s.refAttr());
        if (has(s, s.titleAttr())) a.add(s.titleAttr());
        if (s.descriptionAttr() != null && has(s, s.descriptionAttr())) a.add(s.descriptionAttr());
        return a;
    }

    private boolean has(AgentSource s, String attr) {
        try {
            EntityType<?> et = em.getMetamodel().entity(s.entity());
            et.getAttribute(attr);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Found flatten(AgentSource s, Object entity) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String f : s.fields()) {
            Object v = read(entity, f);
            if (v != null && !(v instanceof String str && str.isBlank())) fields.put(f, v instanceof Enum<?> e ? e.name() : v);
        }
        return new Found(
                read(entity, "id"),
                str(s.refAttr() == null ? null : read(entity, s.refAttr())),
                str(read(entity, s.titleAttr())),
                str(s.descriptionAttr() == null ? null : read(entity, s.descriptionAttr())),
                str(s.statusAttr() == null ? null : read(entity, s.statusAttr())),
                str(s.severityAttr() == null ? null : read(entity, s.severityAttr())),
                fields);
    }

    /** Reads a JPA attribute through the metamodel's Java member — no getter naming guesses. */
    private Object read(Object entity, String attr) {
        if (attr == null) return null;
        try {
            var a = em.getMetamodel().entity(entity.getClass()).getAttribute(attr);
            var member = a.getJavaMember();
            if (member instanceof java.lang.reflect.Field f) {
                f.setAccessible(true);
                return f.get(entity);
            }
            if (member instanceof java.lang.reflect.Method m) {
                m.setAccessible(true);
                return m.invoke(entity);
            }
        } catch (Exception e) {
            log.debug("[AGENT] cannot read {}.{}: {}", entity.getClass().getSimpleName(), attr, e.getMessage());
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) return null;
        if (o instanceof Enum<?> e) return e.name();
        return String.valueOf(o);
    }
}
