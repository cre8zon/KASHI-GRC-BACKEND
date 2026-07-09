package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.UserPermissionOverride;

import java.time.LocalDateTime;
import java.util.List;

/** Criteria API fragment for UserPermissionOverrideRepository. */
public interface UserPermissionOverrideRepositoryCustom {

    /** Active, non-expired overrides for a user (expiresAt NULL = never expires). */
    List<UserPermissionOverride> findActiveByUserId(Long userId, LocalDateTime now);

    /** Bulk delete overrides for a permission (CriteriaDelete). Caller must be @Transactional. */
    void deleteByPermissionId(Long permissionId);
}
