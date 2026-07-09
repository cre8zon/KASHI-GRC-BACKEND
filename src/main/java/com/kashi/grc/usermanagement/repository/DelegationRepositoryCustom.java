package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Delegation;

import java.time.LocalDateTime;
import java.util.List;

/** Criteria API fragment for DelegationRepository. */
public interface DelegationRepositoryCustom {

    /**
     * ACTIVE, non-expired delegations for a tenant. userId (matches either
     * delegator or delegatee) and scopeType are optional filters — the former
     * "(:param IS NULL OR ...)" JPQL trick is now conditional predicates.
     */
    List<Delegation> findActive(Long tenantId, Long userId, String scopeType, LocalDateTime now);

    /**
     * Count ACTIVE delegations TO this user (delegatee) that haven't expired.
     * The former JPQL used CURRENT_TIMESTAMP (DB clock); this uses the app
     * clock — negligible for delegation expiry granularity.
     */
    long countActiveDelegationsToMe(Long userId);

    /** Count ACTIVE delegations BY this user (delegator) that haven't expired. */
    long countActiveDelegationsByMe(Long userId);
}
