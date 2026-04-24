package com.kashi.grc.common.criteria;

import com.kashi.grc.common.dto.NameValue;
import com.kashi.grc.common.dto.PageDetails;
import com.kashi.grc.common.dto.PaginatedResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Central JPA Criteria API helper.
 *
 * <p>Replaces ALL {@code @Query} annotations on list/search endpoints.
 * Every paginated list in the project goes through this class so that
 * pagination, multi-field search, filter, and sort are handled consistently
 * in one place.</p>
 *
 * <h3>Usage example (in a service):</h3>
 * <pre>{@code
 * return criteriaHelper.list(
 *     Vendor.class,
 *     VendorResponse.class,
 *     pageDetails,
 *     (cb, root) -> List.of(
 *         cb.equal(root.get("tenantId"), tenantId),
 *         cb.isFalse(root.get("isDeleted"))
 *     ),
 *     (cb, root) -> Map.of(
 *         "name",       root.get("name"),
 *         "status",     root.get("status"),
 *         "country",    root.get("country")
 *     ),
 *     entity -> vendorMapper.toResponse(entity)
 * );
 * }</pre>
 *
 * <h3>Query string examples:</h3>
 * <pre>
 * GET /v1/vendors?skip=0&take=20&search=name=cloud&filterBy=status=ACTIVE&sortBy=name&sortDirection=asc
 * GET /v1/users?skip=20&take=10&filterBy=department=IT&sortBy=email&sortDirection=desc
 * </pre>
 */
@Component
public class CriteriaQueryHelper {

    private final EntityManager em;

    public CriteriaQueryHelper(EntityManager em) {
        this.em = em;
    }

    /**
     * Execute a paginated, filtered, searched, sorted query.
     *
     * @param entityClass    The JPA entity class to query.
     * @param responseClass  The DTO class (used only for type inference).
     * @param pageDetails    Pagination / filter / search / sort parameters.
     * @param basePredicates A function that provides mandatory base predicates
     *                       (e.g. tenant isolation, soft-delete).
     * @param searchableFields A map of query-param-name → JPA Path for fields
     *                         that can be searched (LIKE) or filtered (=).
     * @param mapper         Entity → DTO mapper.
     * @param <E>            Entity type.
     * @param <R>            Response DTO type.
     * @return Paginated DTO response.
     */
    public <E, R> PaginatedResponse<R> list(
            Class<E> entityClass,
            Class<R> responseClass,
            PageDetails pageDetails,
            BiFunction<CriteriaBuilder, Root<E>, List<Predicate>> basePredicates,
            BiFunction<CriteriaBuilder, Root<E>, Map<String, Path<?>>> searchableFields,
            java.util.function.Function<E, R> mapper) {

        CriteriaBuilder cb = em.getCriteriaBuilder();

        // ── Data query ────────────────────────────────────────────
        CriteriaQuery<E> cq = cb.createQuery(entityClass);
        Root<E> root = cq.from(entityClass);

        List<Predicate> predicates = buildPredicates(cb, root, basePredicates, searchableFields, pageDetails);
        cq.where(predicates.toArray(new Predicate[0]));
        applySort(cb, cq, root, searchableFields, pageDetails);

        TypedQuery<E> query = em.createQuery(cq);
        query.setFirstResult(pageDetails.getSkip().intValue());
        query.setMaxResults(pageDetails.getTake() > 0 ? pageDetails.getTake() : 20);

        List<R> results = query.getResultList().stream().map(mapper).toList();

        // ── Count query ────────────────────────────────────────────
        long total = count(entityClass, cb, basePredicates, searchableFields, pageDetails);

        return new PaginatedResponse<>(results, total, pageDetails);
    }

    // ── Private helpers ───────────────────────────────────────────

    private <E> List<Predicate> buildPredicates(
            CriteriaBuilder cb,
            Root<E> root,
            BiFunction<CriteriaBuilder, Root<E>, List<Predicate>> basePredicates,
            BiFunction<CriteriaBuilder, Root<E>, Map<String, Path<?>>> searchableFields,
            PageDetails pd) {

        List<Predicate> predicates = new ArrayList<>(basePredicates.apply(cb, root));
        Map<String, Path<?>> fields = searchableFields.apply(cb, root);

        // filterBy — exact match (equality)
        for (NameValue nv : pd.getFilterBy()) {
            Path<?> path = fields.get(nv.getName().toLowerCase());
            if (path != null && nv.getValue() != null) {
                predicates.add(cb.equal(cb.lower(path.as(String.class)), nv.getValue().toLowerCase()));
            }
        }

        // search — LIKE match on named field (e.g. search=name=cloud;email=@acme)
        List<Predicate> searchPredicates = new ArrayList<>();
        for (NameValue nv : pd.getSearch()) {
            Path<?> path = fields.get(nv.getName().toLowerCase());
            if (path != null && nv.getValue() != null) {
                searchPredicates.add(cb.like(
                        cb.lower(path.as(String.class)),
                        "%" + nv.getValue().toLowerCase() + "%"));
            }
        }
        // If multiple search terms, OR them together (same behaviour as a global search box)
        if (!searchPredicates.isEmpty()) {
            predicates.add(cb.or(searchPredicates.toArray(new Predicate[0])));
        }

        return predicates;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <E> void applySort(
            CriteriaBuilder cb,
            CriteriaQuery<E> cq,
            Root<E> root,
            BiFunction<CriteriaBuilder, Root<E>, Map<String, Path<?>>> searchableFields,
            PageDetails pd) {

        Map<String, Path<?>> fields = searchableFields.apply(cb, root);
        List<Order> orders = new ArrayList<>();

        List<NameValue> sortByList  = pd.getSortBy();
        List<NameValue> sortDirList = pd.getSortDirection();

        for (int i = 0; i < sortByList.size(); i++) {
            String fieldName = sortByList.get(i).getValue();
            String dir       = sortDirList.size() > i ? sortDirList.get(i).getValue() : "asc";
            Path<?> path     = fields.get(fieldName.toLowerCase());
            if (path != null) {
                orders.add("desc".equalsIgnoreCase(dir) ? cb.desc(path) : cb.asc(path));
            }
        }

        if (orders.isEmpty()) {
            // Default: sort by createdAt desc if available
            try {
                orders.add(cb.desc(root.get("createdAt")));
            } catch (IllegalArgumentException ignored) {
                // Entity has no createdAt — skip default sort
            }
        }

        if (!orders.isEmpty()) cq.orderBy(orders);
    }

    private <E> long count(
            Class<E> entityClass,
            CriteriaBuilder cb,
            BiFunction<CriteriaBuilder, Root<E>, List<Predicate>> basePredicates,
            BiFunction<CriteriaBuilder, Root<E>, Map<String, Path<?>>> searchableFields,
            PageDetails pd) {

        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<E> root = cq.from(entityClass);
        cq.select(cb.count(root));
        List<Predicate> predicates = buildPredicates(cb, root, basePredicates, searchableFields, pd);
        cq.where(predicates.toArray(new Predicate[0]));
        Long result = em.createQuery(cq).getSingleResult();
        return result != null ? result : 0L;
    }
}
