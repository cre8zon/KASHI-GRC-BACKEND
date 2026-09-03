package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.ContentAuthor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentAuthorRepository extends JpaRepository<ContentAuthor, Long> {
    Optional<ContentAuthor> findBySlug(String slug);
    Optional<ContentAuthor> findByUserId(Long userId);

    /** The profile behind a signed-in user, ignoring deactivated ones. */
    Optional<ContentAuthor> findByUserIdAndActiveTrue(Long userId);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);
    List<ContentAuthor> findByActiveTrueOrderByDisplayNameAsc();
}