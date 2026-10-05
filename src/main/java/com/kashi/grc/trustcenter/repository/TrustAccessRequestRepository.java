package com.kashi.grc.trustcenter.repository;

import com.kashi.grc.trustcenter.domain.TrustAccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrustAccessRequestRepository extends JpaRepository<TrustAccessRequest, Long> {

    /**
     * Token lookup, with NO tenant filter — deliberately.
     *
     * The caller is anonymous and has no tenant to filter by; the token IS the
     * credential. The tenant is read back OFF the row that is found, and every
     * subsequent check uses that. Passing a caller-supplied tenant here would be
     * the vulnerability, not the absence of one.
     */
    Optional<TrustAccessRequest> findByAccessToken(String accessToken);

    List<TrustAccessRequest> findByTenantId(Long tenantId);

    List<TrustAccessRequest> findByTenantIdAndRequesterEmail(Long tenantId, String requesterEmail);
}
