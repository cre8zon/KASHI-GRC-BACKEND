package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.UserTenantMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserTenantMembershipRepository extends JpaRepository<UserTenantMembership, Long>,
        UserTenantMembershipRepositoryCustom {

    /** Every tenant this identity can act in, usable or not — the account screen shows all. */
    List<UserTenantMembership> findByUserId(Long userId);

    /** The tenants offered at login. Expiry is filtered in Java via isUsable(). */
    List<UserTenantMembership> findByUserIdAndStatus(Long userId, String status);

    /** Resolves the active membership for a request — user id from the JWT subject, tenant from the tenant_id claim. */
    Optional<UserTenantMembership> findByUserIdAndTenantId(Long userId, Long tenantId);

    /** Guests a client has admitted, for the "external auditors" admin screen. */
    List<UserTenantMembership> findByTenantIdAndMembershipType(Long tenantId, String membershipType);

    /** Everyone a given firm has inside a given client — the unit of firm-level revoke. */
    List<UserTenantMembership> findByTenantIdAndFirmTenantId(Long tenantId, Long firmTenantId);


    /**
     * Role ids for one membership. Roles are global rows, so the membership is
     * the only thing that says which of them apply in which tenant.
     */
    @Query(value = "SELECT ur.role_id FROM user_roles ur WHERE ur.membership_id = :membershipId",
            nativeQuery = true)
    List<Long> findRoleIdsByMembershipId(@Param("membershipId") Long membershipId);

    /**
     * Revoke a firm's access to one tenant in a single statement. Existing
     * engagements and recorded results are untouched — this only stops the
     * firm's people opening them, matching how Vanta treats firm removal.
     */
    @Query(value = """
            UPDATE user_tenant_memberships
               SET status = 'REVOKED', updated_at = NOW()
             WHERE tenant_id = :tenantId AND firm_tenant_id = :firmTenantId
            """, nativeQuery = true)
    @org.springframework.data.jpa.repository.Modifying
    int revokeFirm(@Param("tenantId") Long tenantId, @Param("firmTenantId") Long firmTenantId);
}