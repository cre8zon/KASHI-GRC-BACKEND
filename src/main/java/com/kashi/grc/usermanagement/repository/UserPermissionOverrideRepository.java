package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.UserPermissionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserPermissionOverrideRepository extends JpaRepository<UserPermissionOverride, Long> {

    /**
     * Active overrides for a user — for WorkflowAccessService hot path.
     * Excludes expired and revoked overrides.
     */
    @Query("""
        SELECT o FROM UserPermissionOverride o
        WHERE o.userId = :userId
          AND o.isActive = true
          AND (o.expiresAt IS NULL OR o.expiresAt > :now)
        """)
    List<UserPermissionOverride> findActiveByUserId(
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now);

    /** All overrides for admin list view — paginated via JpaSpecificationExecutor */
    List<UserPermissionOverride> findByUserId(Long userId);

    /** Find existing override — for upsert checks */
    Optional<UserPermissionOverride> findByUserIdAndPermissionId(Long userId, Long permissionId);

    /** Remove all overrides when a permission is deleted */
    @Modifying
    @Query("DELETE FROM UserPermissionOverride o WHERE o.permissionId = :permissionId")
    void deleteByPermissionId(@Param("permissionId") Long permissionId);
}