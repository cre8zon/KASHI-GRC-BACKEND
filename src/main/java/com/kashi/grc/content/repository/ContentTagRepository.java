package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.ContentTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentTagRepository extends JpaRepository<ContentTag, Long> {
    Optional<ContentTag> findBySlug(String slug);
    boolean existsBySlug(String slug);
    List<ContentTag> findByIdIn(List<Long> ids);
    List<ContentTag> findAllByOrderByNameAsc();
}
