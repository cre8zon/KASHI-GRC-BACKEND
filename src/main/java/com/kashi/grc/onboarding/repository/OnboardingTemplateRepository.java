package com.kashi.grc.onboarding.repository;

import com.kashi.grc.onboarding.domain.OnboardingTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OnboardingTemplateRepository extends JpaRepository<OnboardingTemplate, Long> {
    List<OnboardingTemplate> findByTenantIdAndIsActiveTrueOrderByNameAsc(Long tenantId);
}