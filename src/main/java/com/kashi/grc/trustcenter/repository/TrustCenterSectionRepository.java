package com.kashi.grc.trustcenter.repository;

import com.kashi.grc.trustcenter.domain.TrustCenterSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrustCenterSectionRepository extends JpaRepository<TrustCenterSection, Long> {
    List<TrustCenterSection> findByTrustCenterIdAndIsVisibleTrueOrderBySortOrderAsc(Long trustCenterId);
    List<TrustCenterSection> findByTrustCenterIdOrderBySortOrderAsc(Long trustCenterId);
}
