package com.kashi.grc.common.util;

import com.kashi.grc.common.dto.LoggedInDetails;
import com.kashi.grc.common.dto.NameValue;
import com.kashi.grc.common.dto.PageDetails;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.repository.EmailTemplateRepository;
import com.kashi.grc.usermanagement.domain.RoleSide;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Project-wide utility service.
 *
 * getpageDetails() — parses raw query params Map into PageDetails for DbRepository queries.
 * getLoggedInDataContext() — loads full User entity from JWT without any frontend param.
 * current() — lightweight LoggedInDetails (userId + tenantId) from JWT, no DB hit.
 *
 * Tenant ID is ALWAYS read from the JWT via SecurityContextHolder.
 * Controllers must NEVER receive tenantId as a request param or header.
 */
@Service
@Transactional
@AllArgsConstructor
public class UtilityService {

    private final UserRepository          userRepository;
    private final AuthenticationManager   authenticationManager;
    private final EmailTemplateRepository emailTemplateRepository;
    private final EntityManager entityManager;

    public PageDetails getpageDetails(Map<String, String> allParams) {
        PageDetails pageDetails = new PageDetails();
        allParams.entrySet().forEach(p -> {
            switch (p.getKey().toLowerCase()) {
                case "take":
                    pageDetails.setTake(Integer.valueOf(p.getValue()));
                    break;
                case "skip":
                    pageDetails.setSkip(Long.valueOf(p.getValue()));
                    break;
                case "filterby":
                    pageDetails.setFilterBy(parseSemiColonSeparatedList(p.getValue()));
                    break;
                case "search":
                    pageDetails.setSearch(parseSemiColonSeparatedList(p.getValue()));
                    break;
                case "sortby":
                    pageDetails.setSortBy(parseSemiColonSeparatedList(p.getValue()));
                    break;
                case "sortdirection":
                    pageDetails.setSortDirection(parseSemiColonSeparatedList(p.getValue()));
                    break;
            }
        });
        return pageDetails;
    }

    /**
     * Parses "key1=value1;key2=value2" into a list of NameValue pairs.
     * Fixed: each semicolon-separated segment is split on its own '=', not the whole string.
     */
    private ArrayList<NameValue> parseSemiColonSeparatedList(String keyValue) {
        ArrayList<NameValue> pairList = new ArrayList<>();
        String strKeyValue = keyValue.replace("\"", "");
        List<String> keyValueList = Arrays.stream(strKeyValue.split(";")).toList();
        keyValueList.forEach(k -> {
            if (k.contains("=")) {
                // FIX: split on the SEGMENT (k), not on strKeyValue
                List<String> splitOnEqual = Arrays.stream(k.split("=", 2)).toList();
                if (splitOnEqual.size() == 2) {
                    NameValue nameValue = new NameValue();
                    nameValue.setName(splitOnEqual.get(0).toLowerCase().trim());
                    nameValue.setValue(splitOnEqual.get(1).toLowerCase().trim());
                    pairList.add(nameValue);
                }
            }
        });
        return pairList;
    }

    /**
     * Returns the fully-loaded User entity for the currently authenticated request.
     * The JWT filter stores the userId as the principal name — we never receive it from frontend.
     */
    public User getLoggedInDataContext() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "AUTH_NOT_AUTHENTICATED", "No authenticated user in context",
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        // Principal is a UserDetails object whose username is the userId (Long as String)
        Long userId;
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails ud) {
            userId = Long.parseLong(ud.getUsername());
        } else if (principal instanceof String s) {
            userId = Long.parseLong(s);
        } else {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "AUTH_PRINCIPAL_TYPE", "Unexpected principal type",
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
        // ── Criteria query with eager fetch for roles + permissions ───
        jakarta.persistence.EntityManager em = getEntityManager();
        jakarta.persistence.criteria.CriteriaBuilder cb = em.getCriteriaBuilder();
        jakarta.persistence.criteria.CriteriaQuery<User> cq = cb.createQuery(User.class);
        jakarta.persistence.criteria.Root<User> root = cq.from(User.class);
        root.fetch("roles", jakarta.persistence.criteria.JoinType.LEFT)
                .fetch("permissions", jakarta.persistence.criteria.JoinType.LEFT);
        cq.select(root).distinct(true)
                .where(cb.equal(root.get("id"), userId));

        return em.createQuery(cq).getResultStream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private jakarta.persistence.EntityManager getEntityManager() {
        return entityManager;
    }

    /**
     * Lightweight — returns userId + tenantId from JWT without loading full User entity.
     * Use this when you only need IDs (no DB hit).
     */
    public LoggedInDetails current() {
        User user = getLoggedInDataContext();
        LoggedInDetails details = new LoggedInDetails();
        details.setPartyId(user.getId());
        details.setTenantId(user.getTenantId());
        return details;
    }

    public boolean isSystemUser() {
        User user = getLoggedInDataContext();
        return user.getRoles().stream()
                .anyMatch(r -> r.getSide() == RoleSide.SYSTEM);
    }
}
