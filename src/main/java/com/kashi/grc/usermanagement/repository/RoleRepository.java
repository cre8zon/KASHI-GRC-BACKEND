package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.RoleSide;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Tenant-overlay lists and the user-count join live in the Custom fragment. */
public interface RoleRepository
        extends JpaRepository<Role, Long>, RoleRepositoryCustom {

    Optional<Role> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Role> findByNameAndTenantId(String name, Long tenantId);

    boolean existsByNameAndSideAndTenantId(String name, RoleSide side, Long tenantId);

    Optional<Role> findByNameAndSide(String name, RoleSide side);

    long countByTenantId(Long tenantId);

    long countByTenantIdIsNull();
}
