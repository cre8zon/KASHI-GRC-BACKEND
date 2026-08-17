package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.AuditorAccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditorAccessRequestRepository extends JpaRepository<AuditorAccessRequest, Long> {

    /** Incoming requests for a client — their decision queue. */
    List<AuditorAccessRequest> findByClientTenantIdOrderByCreatedAtDesc(Long clientTenantId);

    /** A firm's own requests, so they can see what is outstanding. */
    List<AuditorAccessRequest> findByFirmTenantIdOrderByCreatedAtDesc(Long firmTenantId);

    /**
     * Used to block a second PENDING request for the same pair. Declined and
     * withdrawn ones are left alone deliberately: a firm should be able to ask
     * again after a decline, and the history of who asked and who refused is
     * worth keeping.
     */
    Optional<AuditorAccessRequest> findByFirmTenantIdAndClientTenantIdAndStatus(
            Long firmTenantId, Long clientTenantId, String status);
}