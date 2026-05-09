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

    /**
     * Find ALL mappings whose score range contains the given score.
     * Returns multiple results when the admin has mapped 2–3 templates to the
     * same tier (e.g. three different LOW templates for the assessor to choose from).
     * Returns a single-element list for the default 1-template-per-tier setup.
     */
    default List<RiskTemplateMapping> findAllByScore(BigDecimal score) {
        return findByTenantIdIsNull().stream()
                .filter(m -> m.getMinScore() != null && m.getMaxScore() != null
                        && score.compareTo(m.getMinScore()) >= 0
                        && score.compareTo(m.getMaxScore()) <= 0)
                .collect(java.util.stream.Collectors.toList());
    }
}