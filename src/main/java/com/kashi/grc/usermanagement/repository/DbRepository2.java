package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.common.dto.PageDetails;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.dto.response.UserResponse;
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
@Transactional
public class DbRepository2 {
    @PersistenceContext
    EntityManager entityManager;

    //TODO: This method is just for testing purpose, we can remove this later and implement the pagination in service layer itself.
    public List<UserResponse> getAllUsers(PageDetails pageDetails, Long tenantId) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<User> criteriaQuery = criteriaBuilder.createQuery(User.class);
        Root<User> user = criteriaQuery.from(User.class);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(criteriaBuilder.equal(user.get("tenant").get("id"), tenantId));
        if (pageDetails != null) {
            if (pageDetails.getSearch() != null) {
                pageDetails.getSearch().forEach(search -> {
                    switch (search.getName()) {
                        case "name":
                            predicates.add(criteriaBuilder.like(user.get("name"), "%" + search.getValue() + "%"));
                    }
                });
            }
            if (pageDetails.getFilterBy() != null) {
                pageDetails.getFilterBy().forEach(filterBy -> {
                    switch (filterBy.getName()) {
                        case "name":
                            predicates.add(criteriaBuilder.equal(user.get("name"), filterBy.getValue()));
                    }
                });
            }
            if (pageDetails.getSortBy() != null) {
                pageDetails.getSortBy().forEach(sortBy -> {
                    switch (sortBy.getName()) {
                        case "name":
                            criteriaQuery.orderBy(criteriaBuilder.asc(user.get("name")));
                    }
                });
            }
        }
        criteriaQuery.where(predicates.toArray(new Predicate[0]));
        Query query = entityManager.createQuery(criteriaQuery);
        query.setFirstResult(pageDetails.getSkip().intValue());
        query.setMaxResults(pageDetails.getTake());
        entityManager.close();
        return query.getResultList();
    }
}
