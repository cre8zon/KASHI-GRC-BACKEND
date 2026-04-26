package com.kashi.grc.common.repository;

import com.kashi.grc.common.dto.NameValue;
import com.kashi.grc.common.dto.PageDetails;
import com.kashi.grc.common.dto.PaginatedResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Project-wide JPA Criteria API repository.
 *
 * This is the ONLY place in the project where dynamic queries are built.
 * All list endpoints that need search / filter / sort / pagination call this class.
 * No {@code @Query} annotations allowed anywhere in the project.
 *
 * Usage example (in any service):
 * <pre>{@code
 * return dbRepository.findAll(
 *     Vendor.class,
 *     pageDetails,
 *     (cb, root) -> List.of(
 *         cb.equal(root.get("tenantId"), tenantId),
 *         cb.isFalse(root.get("isDeleted"))
 *     ),
 *     (cb, root) -> Map.of(
 *         "name",    root.get("name"),
 *         "status",  root.get("status"),
 *         "country", root.get("country")
 *     ),
 *     vendor -> toResponse(vendor)
 * );
 * }</pre>
 *
 * Query string format for controllers:
 *   GET /v1/vendors?skip=0&take=20
 *     &search=name=cloud;country=IN
 *     &filterBy=status=ACTIVE
 *     &sortBy=name=asc
 */
@Repository
@Transactional
public class DbRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Execute a fully paginated, filtered, searched, sorted JPA Criteria query.
     *
     * @param entityClass      JPA entity class to query
     * @param pageDetails      Pagination/filter/search/sort params from request
     * @param basePredicates   Mandatory predicates (tenant isolation, soft-delete, etc.)
     * @param searchableFields Map of param-name → JPA Path (defines which fields are queryable)
     * @param mapper           Entity → DTO mapper function
     */
    public <E, R> PaginatedResponse<R> findAll(
            Class<E> entityClass,
            PageDetails pageDetails,
            BiFunction<CriteriaBuilder, Root<E>, List<Predicate>> basePredicates,
            BiFunction<CriteriaBuilder, Root<E>, Map<String, Path<?>>> searchableFields,
            Function<E, R> mapper) {

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        // ── Data query ────────────────────────────────────────────
        CriteriaQuery<E> cq = cb.createQuery(entityClass);
        Root<E> root = cq.from(entityClass);

        List<Predicate> predicates = buildPredicates(cb, root, basePredicates, searchableFields, pageDetails);
        cq.where(predicates.toArray(new Predicate[0]));
        applySort(cb, cq, root, searchableFields, pageDetails);

        var query = entityManager.createQuery(cq);
        long skip = pageDetails.getSkip() != null ? pageDetails.getSkip() : 0L;
        int  take = pageDetails.getTake() != null && pageDetails.getTake() > 0 ? pageDetails.getTake() : 10;
        query.setFirstResult((int) skip);
        query.setMaxResults(take);

        List<R> results = query.getResultList().stream().map(mapper).toList();

        // ── Count query ───────────────────────────────────────────
        long total = count(entityClass, cb, basePredicates, searchableFields, pageDetails);

        return new PaginatedResponse<>(results, total, pageDetails);
    }

    // Add to DbRepository.java
    public List<Long> findUserIdsByRoleAndTenant(Long roleId, Long tenantId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<com.kashi.grc.usermanagement.domain.User> root =
                cq.from(com.kashi.grc.usermanagement.domain.User.class);

        Join<Object, Object> rolesJoin = root.join("roles", JoinType.INNER);

        cq.select(root.get("id"))
                .distinct(true)
                .where(
                        cb.equal(rolesJoin.get("id"), roleId),
                        cb.equal(root.get("tenantId"), tenantId),
                        cb.isFalse(root.get("isDeleted"))
                );

        return entityManager.createQuery(cq).getResultList();
    }

    /**
     * Vendor-scoped variant: finds users holding a role AND belonging to a specific vendor.
     *
     * Used for VENDOR-side workflow steps so tasks go only to users of the workflow's
     * specific vendor (instance.entityId), not to ALL users with that role across every
     * vendor in the tenant. Without this scope, every VRM/CISO/Responder across all vendors
     * receives the same task — a data isolation violation.
     *
     * null vendorId falls back to the tenant-wide query (safe for org-side steps).
     */
    public List<Long> findUserIdsByRoleAndVendor(Long roleId, Long tenantId, Long vendorId) {
        if (vendorId == null) {
            return findUserIdsByRoleAndTenant(roleId, tenantId);
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<com.kashi.grc.usermanagement.domain.User> root =
                cq.from(com.kashi.grc.usermanagement.domain.User.class);

        Join<Object, Object> rolesJoin = root.join("roles", JoinType.INNER);

        cq.select(root.get("id"))
                .distinct(true)
                .where(
                        cb.equal(rolesJoin.get("id"), roleId),
                        cb.equal(root.get("tenantId"), tenantId),
                        cb.equal(root.get("vendorId"), vendorId),
                        cb.isFalse(root.get("isDeleted"))
                );

        return entityManager.createQuery(cq).getResultList();
    }

    // ── Private helpers ───────────────────────────────────────────

    private <E> List<Predicate> buildPredicates(
            CriteriaBuilder cb, Root<E> root,
            BiFunction<CriteriaBuilder, Root<E>, List<Predicate>> basePredicates,
            BiFunction<CriteriaBuilder, Root<E>, Map<String, Path<?>>> searchableFields,
            PageDetails pd) {

        List<Predicate> predicates = new ArrayList<>(basePredicates.apply(cb, root));
        Map<String, Path<?>> fields = searchableFields.apply(cb, root);

        // filterBy — exact match (equality, case-insensitive)
// filterBy — exact match
        if (pd.getFilterBy() != null) {
            for (NameValue nv : pd.getFilterBy()) {
                Path<?> path = fields.get(nv.getName());
                if (path != null && nv.getValue() != null) {
                    // ── Check if field is a String type — only lowercase for strings ──
                    Class<?> javaType = path.getJavaType();
                    if (String.class.isAssignableFrom(javaType)) {
                        predicates.add(cb.equal(
                                cb.lower(path.as(String.class)),
                                nv.getValue().toLowerCase()
                        ));
                    } else if (javaType.isEnum()) {
                        // Enum — convert string to enum value for proper comparison
                        try {
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            Enum enumValue = Enum.valueOf((Class<Enum>) javaType, nv.getValue().toUpperCase());
                            predicates.add(cb.equal(path, enumValue));
                        } catch (IllegalArgumentException ignored) {
                            // Invalid enum value — skip predicate
                        }
                    } else {
                        predicates.add(cb.equal(path, nv.getValue()));
                    }
                }
            }
        }

        // search — LIKE match; multiple search terms are OR-combined
        if (pd.getSearch() != null && !pd.getSearch().isEmpty()) {
            List<Predicate> searchPreds = new ArrayList<>();
            for (NameValue nv : pd.getSearch()) {
                Path<?> path = fields.get(nv.getName());
                if (path != null && nv.getValue() != null) {
                    searchPreds.add(cb.like(
                            cb.lower(path.as(String.class)),
                            "%" + nv.getValue().toLowerCase() + "%"
                    ));
                }
            }
            if (!searchPreds.isEmpty()) {
                predicates.add(cb.or(searchPreds.toArray(new Predicate[0])));
            }
        }

        return predicates;
    }

    private <E> void applySort(
            CriteriaBuilder cb, CriteriaQuery<E> cq, Root<E> root,
            BiFunction<CriteriaBuilder, Root<E>, Map<String, Path<?>>> searchableFields,
            PageDetails pd) {

        Map<String, Path<?>> fields = searchableFields.apply(cb, root);
        List<Order> orders = new ArrayList<>();

        // sortBy list: each entry is name=fieldName, value=direction (or just the field)
        if (pd.getSortBy() != null) {
            List<NameValue> sortByList  = pd.getSortBy();
            List<NameValue> sortDirList = pd.getSortDirection() != null ? pd.getSortDirection() : List.of();

            for (int i = 0; i < sortByList.size(); i++) {
                NameValue sortEntry = sortByList.get(i);
                // Field name is in the value (e.g. NameValue{name="name", value="email"})
                String fieldName = sortEntry.getValue() != null ? sortEntry.getValue() : sortEntry.getName();
                String dir = sortDirList.size() > i
                        ? sortDirList.get(i).getValue()
                        : "asc";
                Path<?> path = fields.get(fieldName);
                if (path != null) {
                    orders.add("desc".equalsIgnoreCase(dir) ? cb.desc(path) : cb.asc(path));
                }
            }
        }

        // Default sort: createdAt desc (if entity has it)
        if (orders.isEmpty()) {
            try {
                orders.add(cb.desc(root.get("createdAt")));
            } catch (IllegalArgumentException ignored) { /* entity has no createdAt */ }
        }

        if (!orders.isEmpty()) cq.orderBy(orders);
    }

    private <E> long count(
            Class<E> entityClass, CriteriaBuilder cb,
            BiFunction<CriteriaBuilder, Root<E>, List<Predicate>> basePredicates,
            BiFunction<CriteriaBuilder, Root<E>, Map<String, Path<?>>> searchableFields,
            PageDetails pd) {

        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<E> root = cq.from(entityClass);
        cq.select(cb.count(root));
        List<Predicate> predicates = buildPredicates(cb, root, basePredicates, searchableFields, pd);
        cq.where(predicates.toArray(new Predicate[0]));
        Long result = entityManager.createQuery(cq).getSingleResult();
        return result != null ? result : 0L;
    }
}