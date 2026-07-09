package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** findAllByUserId lives in the Custom fragment (Criteria API). */
public interface PermissionRepository
        extends JpaRepository<Permission, Long>, PermissionRepositoryCustom {

    Optional<Permission> findByCode(String code);

    List<Permission> findByModuleId(Long moduleId);
}
