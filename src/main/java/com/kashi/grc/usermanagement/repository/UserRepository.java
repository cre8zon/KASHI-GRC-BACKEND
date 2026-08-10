package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.domain.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * UserRepository — entity graph annotations control exactly what gets JOIN-fetched.
 *
 * Rule of thumb used here:
 *   WITH_ROLES_PERMISSIONS — auth/security paths (CustomUserDetailsService, login)
 *   WITH_ROLES             — access-control paths (workflow checks, UtilityService)
 *   no graph               — simple lookups where roles are never accessed
 *
 * This replaces EAGER fetch on User.roles and Role.permissions which caused
 * a full JOIN on every user load regardless of whether roles were needed.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    // ── Auth / security — needs full roles + permissions ─────────────────────
    // Spring Security builds GrantedAuthority from role.permissions.code.
    // Without the graph these would be separate SELECT per role per permission.

    /**
     * Token auth path. JwtAuthenticationFilter resolves the user by the JWT
     * subject (the id) on EVERY request, and Spring Security then walks
     * role.permissions to build authorities.
     *
     * Without the graph that is 1 + 1 + one-per-role lazy selects — 6 sequential
     * round trips for a 4-role user, paid before any controller runs. The email
     * variants below already had the graph; this id variant was missed, so every
     * authenticated request in every module paid for it.
     */
    @EntityGraph(User.WITH_ROLES_PERMISSIONS)
    Optional<User> findWithRolesAndPermissionsById(Long id);

    @EntityGraph(User.WITH_ROLES_PERMISSIONS)
    Optional<User> findByEmailAndIsDeletedFalse(String email);

    @EntityGraph(User.WITH_ROLES_PERMISSIONS)
    Optional<User> findByEmail(String email);

    // ── Access control — needs roles only, not permissions ────────────────────
    // WorkflowAccessService, UtilityService check role.name / role.side only.
    // Loading permissions here would be wasted I/O.

    @EntityGraph(User.WITH_ROLES)
    Optional<User> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    @EntityGraph(User.WITH_ROLES)
    Optional<User> findByIdAndTenantId(Long id, Long tenantId);

    // ── Simple lookups — no graph (roles never accessed on these paths) ───────

    Optional<User> findByPasswordResetTokenAndIsDeletedFalse(String token);

    // ── Existence checks ──────────────────────────────────────────────────────
    boolean existsByEmailAndTenantIdAndIsDeletedFalse(String email, Long tenantId);

    /** Global (not tenant-scoped) — email is now a globally unique identity,
     *  matching what login() has always assumed. Excludes soft-deleted rows
     *  so a previously offboarded email can legitimately be re-invited later. */
    boolean existsByEmailAndIsDeletedFalse(String email);

    boolean existsByEmail(String email);

    // ── Counts ────────────────────────────────────────────────────────────────
    long countByTenantId(Long tenantId);

    long countByTenantIdAndStatus(Long tenantId, UserStatus status);

    // ── List queries — no graph (list pages show names/email, not role details)
    // If a list endpoint needs roles, add @EntityGraph(User.WITH_ROLES) here.
    List<User> findByTenantIdAndIsDeletedFalse(Long tenantId);

    List<User> findByVendorIdAndIsDeletedFalse(Long vendorId);
}