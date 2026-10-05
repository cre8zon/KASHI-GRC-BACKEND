package com.kashi.grc.onboarding.repository;

import com.kashi.grc.onboarding.domain.OnboardingTemplateItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OnboardingTemplateItemRepository extends JpaRepository<OnboardingTemplateItem, Long> {
    List<OnboardingTemplateItem> findByTemplateIdAndIsActiveTrueOrderBySortOrderAsc(Long templateId);
}