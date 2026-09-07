package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.ContentCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentCategoryRepository extends JpaRepository<ContentCategory, Long> {
    Optional<ContentCategory> findBySlug(String slug);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);
    List<ContentCategory> findAllByOrderBySortOrderAscNameAsc();
}
