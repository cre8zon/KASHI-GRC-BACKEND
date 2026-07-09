package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Permission;
import java.util.Set;

/** Criteria API fragment for PermissionRepository. */
public interface PermissionRepositoryCustom {

    /** Distinct permissions granted to a user through role membership. */
    Set<Permission> findAllByUserId(Long userId);
}
