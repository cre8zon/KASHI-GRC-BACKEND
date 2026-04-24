package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.TenantBranding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface TenantBrandingRepository extends JpaRepository<TenantBranding, Long> {
    Optional<TenantBranding> findByTenantId(Long tenantId);
}
