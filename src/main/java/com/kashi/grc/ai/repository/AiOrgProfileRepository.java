package com.kashi.grc.ai.repository;

import com.kashi.grc.ai.domain.AiOrgProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiOrgProfileRepository extends JpaRepository<AiOrgProfile, Long> {
    Optional<AiOrgProfile> findByTenantId(Long tenantId);
    boolean existsByTenantId(Long tenantId);
}
