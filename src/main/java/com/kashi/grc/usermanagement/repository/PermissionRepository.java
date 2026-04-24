package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByCode(String code);

    List<Permission> findByModuleId(Long moduleId);

    /** Fetch all permissions a user has via their assigned roles */
    @Query("""
            SELECT DISTINCT p 
            FROM User u 
            JOIN u.roles r 
            JOIN r.permissions p 
            WHERE u.id = :userId
            """)
    Set<Permission> findAllByUserId(@Param("userId") Long userId);
}
