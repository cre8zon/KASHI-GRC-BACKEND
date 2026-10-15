package com.kashi.grc.enquiry.repository;

import com.kashi.grc.enquiry.domain.Enquiry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;

import java.time.LocalDateTime;
import java.util.*;

/** JPA Criteria API implementation of EnquiryRepositoryCustom. */
public class EnquiryRepositoryImpl implements EnquiryRepositoryCustom {

    private static final Set<String> GROUPABLE = Set.of("status", "enquiryType");

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Enquiry> search(Filter f, int page, int size) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Enquiry> cq = cb.createQuery(Enquiry.class);
        Root<Enquiry> e = cq.from(Enquiry.class);
        cq.select(e).where(predicates(cb, e, f));
        cq.orderBy(cb.desc(e.get("createdAt")), cb.desc(e.get("id")));
        return em.createQuery(cq)
                .setFirstResult(Math.max(0, page) * size)
                .setMaxResults(size)
                .getResultList();
    }

    @Override
    public long count(Filter f) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Enquiry> e = cq.from(Enquiry.class);
        cq.select(cb.count(e)).where(predicates(cb, e, f));
        return em.createQuery(cq).getSingleResult();
    }

    @Override
    public Map<String, Long> countBy(String attribute, LocalDateTime since) {
        if (!GROUPABLE.contains(attribute)) throw new IllegalArgumentException("Not groupable: " + attribute);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<Enquiry> e = cq.from(Enquiry.class);
        Path<String> col = e.get(attribute);
        cq.multiselect(col, cb.count(e));
        if (since != null) cq.where(cb.greaterThanOrEqualTo(e.<LocalDateTime>get("createdAt"), since));
        cq.groupBy(col);
        Map<String, Long> out = new LinkedHashMap<>();
        for (Tuple t : em.createQuery(cq).getResultList()) out.put(t.get(0, String.class), t.get(1, Long.class));
        return out;
    }

    @Override
    public Map<String, Long> countBySource(LocalDateTime since) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<Enquiry> e = cq.from(Enquiry.class);
        Expression<String> source = cb.<String>selectCase()
                .when(cb.isNotNull(e.get("referralCode")), cb.literal("Audit-firm referral"))
                .when(cb.isNotNull(e.get("utmSource")), e.<String>get("utmSource"))
                .otherwise(cb.literal("Direct / organic"));
        cq.multiselect(source, cb.count(e));
        if (since != null) cq.where(cb.greaterThanOrEqualTo(e.<LocalDateTime>get("createdAt"), since));
        cq.groupBy(source);
        Map<String, Long> out = new TreeMap<>();
        for (Tuple t : em.createQuery(cq).getResultList()) out.put(t.get(0, String.class), t.get(1, Long.class));
        return out;
    }

    @Override
    public List<LocalDateTime[]> responseTimes(LocalDateTime since) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<Enquiry> e = cq.from(Enquiry.class);
        cq.multiselect(e.<LocalDateTime>get("createdAt"), e.<LocalDateTime>get("firstResponseAt"));
        List<Predicate> p = new ArrayList<>();
        p.add(cb.isNotNull(e.get("firstResponseAt")));
        if (since != null) p.add(cb.greaterThanOrEqualTo(e.<LocalDateTime>get("createdAt"), since));
        cq.where(p.toArray(new Predicate[0]));
        List<LocalDateTime[]> out = new ArrayList<>();
        for (Tuple t : em.createQuery(cq).getResultList()) {
            out.add(new LocalDateTime[]{ t.get(0, LocalDateTime.class), t.get(1, LocalDateTime.class) });
        }
        return out;
    }

    private Predicate[] predicates(CriteriaBuilder cb, Root<Enquiry> e, Filter f) {
        List<Predicate> p = new ArrayList<>();
        if (f == null) return new Predicate[0];
        if (f.types() != null && !f.types().isEmpty())       p.add(e.get("enquiryType").in(f.types()));
        if (f.statuses() != null && !f.statuses().isEmpty()) p.add(e.get("status").in(f.statuses()));
        if (f.assignedTo() != null)                          p.add(cb.equal(e.get("assignedTo"), f.assignedTo()));
        if (f.from() != null) p.add(cb.greaterThanOrEqualTo(e.<LocalDateTime>get("createdAt"), f.from()));
        if (f.to() != null)   p.add(cb.lessThan(e.<LocalDateTime>get("createdAt"), f.to()));
        if (f.q() != null && !f.q().isBlank()) {
            String like = "%" + f.q().trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
            p.add(cb.or(
                    cb.like(cb.lower(e.get("fullName")), like),
                    cb.like(cb.lower(e.get("email")), like),
                    cb.like(cb.lower(e.get("company")), like),
                    cb.like(cb.lower(e.get("reference")), like)
            ));
        }
        return p.toArray(new Predicate[0]);
    }
}
