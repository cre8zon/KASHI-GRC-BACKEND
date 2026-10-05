package com.kashi.grc.trustcenter.repository;

import com.kashi.grc.trustcenter.domain.TrustCenter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TrustCenterRepository extends JpaRepository<TrustCenter, Long> {

    /** The public lookup. Published-only is enforced in the service, not here,
     *  so the admin side can load its own unpublished page with the same call. */
    Optional<TrustCenter> findBySlugAndIsDeletedFalse(String slug);

    Optional<TrustCenter> findByTenantIdAndIsDeletedFalse(Long tenantId);

    boolean existsBySlug(String slug);
}
