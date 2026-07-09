package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.PermissionGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** RBAC read/delete query logic lives in the Custom fragment (Criteria API). */
@Repository
public interface PermissionGrantRepository
        extends JpaRepository<PermissionGrant, Long>, PermissionGrantRepositoryCustom {

    List<PermissionGrant> findByRoleId(Long roleId);

    Optional<PermissionGrant> findByRoleIdAndPermissionId(Long roleId, Long permissionId);

    boolean existsByRoleIdAndPermissionId(Long roleId, Long permissionId);

    List<PermissionGrant> findByPermissionId(Long permissionId);
}
