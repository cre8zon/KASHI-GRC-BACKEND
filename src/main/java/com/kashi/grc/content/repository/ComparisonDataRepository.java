package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.ComparisonData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ComparisonDataRepository extends JpaRepository<ComparisonData, Long> {
    Optional<ComparisonData> findBySlug(String slug);
    List<ComparisonData> findByActiveTrueOrderByCompetitorNameAsc();
    List<ComparisonData> findByIdIn(List<Long> ids);
}
