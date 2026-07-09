package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.UserPermissionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Active-override lookup and bulk delete live in the Custom fragment. */
@Repository
public interface UserPermissionOverrideRepository
        extends JpaRepository<UserPermissionOverride, Long>, UserPermissionOverrideRepositoryCustom {

    List<UserPermissionOverride> findByUserId(Long userId);

    Optional<UserPermissionOverride> findByUserIdAndPermissionId(Long userId, Long permissionId);
}
