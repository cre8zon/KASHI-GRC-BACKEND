package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.RoleSide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByIdAndTenantId(Long id, Long tenantId);

    /** Used by DataInitializer to find or create the PLATFORM_ADMIN role idempotently */
    Optional<Role> findByNameAndTenantId(String name, Long tenantId);

    boolean existsByNameAndSideAndTenantId(String name, RoleSide side, Long tenantId);

    Optional<Role> findByNameAndSide(String name, RoleSide side);

    /** System roles (tenantId = null) + tenant-specific roles */
    @Query("SELECT r FROM Role r WHERE r.tenantId = :tenantId OR r.tenantId IS NULL")
    List<Role> findAllForTenant(@Param("tenantId") Long tenantId);

    /** Filter by side (null = all sides) */
    @Query("SELECT r FROM Role r WHERE (r.tenantId = :tenantId OR r.tenantId IS NULL) AND (:side IS NULL OR r.side = :side)")
    List<Role> findAllForTenantBySide(@Param("tenantId") Long tenantId, @Param("side") RoleSide side);

    /** Count users that have this role assigned */
    @Query("SELECT COUNT(u) FROM User u JOIN u.roles r WHERE r.id = :roleId")
    long countUsersWithRole(@Param("roleId") Long roleId);
}