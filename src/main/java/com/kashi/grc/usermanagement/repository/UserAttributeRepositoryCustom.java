package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.UserAttribute;

import java.util.List;
import java.util.Optional;

/** Criteria API fragment for UserAttributeRepository. */
public interface UserAttributeRepositoryCustom {

    /** All attributes for a user (navigates the mapped user association). */
    List<UserAttribute> findByUserId(Long userId);

    /** Single attribute by user + key. */
    Optional<UserAttribute> findByUserIdAndAttributeKey(Long userId, String key);

    /** Bulk delete one attribute (CriteriaDelete). Caller must be @Transactional. */
    void deleteByUserIdAndAttributeKey(Long userId, String key);

    /** Bulk delete all attributes for a user. Caller must be @Transactional. */
    void deleteByUserId(Long userId);
}
