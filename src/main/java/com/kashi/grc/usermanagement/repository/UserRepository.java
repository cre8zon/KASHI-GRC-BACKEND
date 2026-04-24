package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.domain.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // ── Lookup ────────────────────────────────────────────────────
    Optional<User> findByEmailAndIsDeletedFalse(String email);

    Optional<User> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    Optional<User> findByIdAndTenantId(Long id, Long tenantId);

    Optional<User> findByPasswordResetTokenAndIsDeletedFalse(String token);

    Optional<User> findByEmail(String email);

    // ── Existence checks ──────────────────────────────────────────
    boolean existsByEmailAndTenantIdAndIsDeletedFalse(String email, Long tenantId);

    /** Used by DataInitializer to check admin existence regardless of tenantId */
    boolean existsByEmail(String email);

    // ── Counts ────────────────────────────────────────────────────
    long countByTenantId(Long tenantId);

    long countByTenantIdAndStatus(Long tenantId, UserStatus status);

    // ── List ──────────────────────────────────────────────────────
    List<User> findByTenantIdAndIsDeletedFalse(Long tenantId);

    List<User> findByVendorIdAndIsDeletedFalse(Long vendorId);
}