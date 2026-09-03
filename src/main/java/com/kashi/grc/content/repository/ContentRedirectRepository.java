package com.kashi.grc.content.repository;

import com.kashi.grc.content.domain.ContentRedirect;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentRedirectRepository extends JpaRepository<ContentRedirect, Long> {
    Optional<ContentRedirect> findByFromPath(String fromPath);
    List<ContentRedirect> findByActiveTrue();
    List<ContentRedirect> findByToPath(String toPath);
    List<ContentRedirect> findByPostId(Long postId);
}
