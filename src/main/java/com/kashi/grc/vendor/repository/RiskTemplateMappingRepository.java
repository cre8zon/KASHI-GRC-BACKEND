package com.kashi.grc.vendor.repository;

import com.kashi.grc.vendor.domain.RiskTemplateMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface RiskTemplateMappingRepository extends JpaRepository<RiskTemplateMapping, Long> {

    //List<RiskTemplateMapping> findByTenantId(Long tenantId);

    List<RiskTemplateMapping> findByTenantIdIsNull();

    //void deleteByTenantId(Long tenantId);

    void deleteByTenantIdIsNull();

    /**
     * Find the mapping whose score range contains the given score.
     * Uses a simple derived query — Criteria API handles the complex case.
     */
    default Optional<RiskTemplateMapping> findByScore(BigDecimal score) {  // ← remove Long tenantId
        return findByTenantIdIsNull().stream()
                .filter(m -> m.getMinScore() != null && m.getMaxScore() != null
                        && score.compareTo(m.getMinScore()) >= 0
                        && score.compareTo(m.getMaxScore()) <= 0)
                .findFirst();
    }
}
