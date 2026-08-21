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
     *
     * That statement used to live here, matching on
     * user_tenant_memberships.grant_id. UserTenantMembership has no grantId
     * field and nothing ever populated the column, so it revoked nobody while
     * reporting success. It has been removed rather than repaired: the firm,
     * not the grant, is what memberships actually record, so the revoke belongs
     * next to that data as UserTenantMembershipRepository.revokeFirm(tenantId,
     * firmTenantId). Leaving a second, subtly broken revoke here would only
     * invite someone to call it.
     */
}