package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.FirmAccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FirmAccessGrantRepository extends JpaRepository<FirmAccessGrant, Long> {

    /** Firms a client has admitted — the client's "external auditors" screen. */
    List<FirmAccessGrant> findByClientTenantId(Long clientTenantId);

    /** Clients a firm may staff — the firm's own client list. */
    List<FirmAccessGrant> findByFirmTenantIdAndStatus(Long firmTenantId, String status);

    /** The single grant governing one client/firm pair. */
    Optional<FirmAccessGrant> findByClientTenantIdAndFirmTenantId(Long clientTenantId, Long firmTenantId);

    /**
     * Revoking a grant must take the firm's people with it, or the client would
     * remove the firm and its auditors would keep working.
     */
    @Modifying
    @Query(value = """
            UPDATE user_tenant_memberships
               SET status = 'REVOKED', updated_at = NOW()
             WHERE grant_id = :grantId AND status <> 'REVOKED'
            """, nativeQuery = true)
    int revokeMembershipsForGrant(@Param("grantId") Long grantId);
}