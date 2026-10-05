package com.kashi.grc.onboarding.repository;

import com.kashi.grc.onboarding.domain.PersonnelOnboardingItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PersonnelOnboardingItemRepository
        extends JpaRepository<PersonnelOnboardingItem, Long> {

    List<PersonnelOnboardingItem> findByPersonnelIdOrderBySortOrderAsc(Long personnelId);

    List<PersonnelOnboardingItem> findByTenantId(Long tenantId);

    /** Used to keep instantiation idempotent when a joiner is re-activated. */
    long countByPersonnelId(Long personnelId);
}