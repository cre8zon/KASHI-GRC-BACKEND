package com.kashi.grc.risk.repository;

import com.kashi.grc.risk.domain.RiskControlLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RiskControlLinkRepository extends JpaRepository<RiskControlLink, Long>,
        RiskControlLinkRepositoryCustom {

    List<RiskControlLink> findByRiskId(Long riskId);

    /** Batch counterpart — one query for a whole page of risks. */
    List<RiskControlLink> findByRiskIdIn(java.util.Collection<Long> riskIds);

    Optional<RiskControlLink> findByRiskIdAndControlId(Long riskId, Long controlId);

    boolean existsByRiskIdAndControlId(Long riskId, Long controlId);

    long countByRiskId(Long riskId);
}
